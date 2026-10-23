package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A nudge after a long stretch in one app: once YouTube has been in front for {@link #WATCH_MS} without
 * a break, a card over it says the TV is about to explode (the owner's joke), then goes away on its own.
 * Launcher settings turns it off ({@link #PREF_KEY}). The accessibility services feed it their window
 * events; there is one instance for the process, so two bound services share one clock. No polling:
 * a single delayed post, cancelled the moment another app comes to the front.
 */
final class WatchTimeWarning {
    static final String PREF_KEY = "youtube_warning";
    static final String WATCHED_PACKAGE = "com.google.android.youtube.tv";
    static final long WATCH_MS = 30 * 60 * 1000L;
    private static final long SHOW_MS = 12_000L;
    /** System overlays (the volume bar) come to the front without being a change of app. */
    private static final String[] OVERLAYS = {"com.android.systemui", "com.xiaomi.mitv.systemui"};

    private static WatchTimeWarning instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable warn = this::show;
    private final Runnable dismiss = this::hide;
    /** The service that last reported a window; it owns the card's window. */
    private AccessibilityService service;
    private boolean watching;
    private View card;

    private WatchTimeWarning() {
    }

    static synchronized WatchTimeWarning get() {
        if (instance == null) instance = new WatchTimeWarning();
        return instance;
    }

    /** A window of {@code pkg} came to the front, reported by {@code from}. */
    void onWindow(AccessibilityService from, CharSequence pkg) {
        for (String overlay : OVERLAYS) if (overlay.contentEquals(pkg)) return;
        service = from;
        if (WATCHED_PACKAGE.contentEquals(pkg)) {
            if (!watching) {
                watching = true;
                handler.postDelayed(warn, WATCH_MS);
            }
        } else {
            stop();
        }
    }

    /** {@code from} is going away: drop the clock and the card if they were its. */
    void detach(AccessibilityService from) {
        if (service != from) return;
        stop();
        service = null;
    }

    private void stop() {
        watching = false;
        handler.removeCallbacks(warn);
        hide();
    }

    private void show() {
        if (!watching || service == null) return;
        // Re-arm whether or not the card shows: a long session gets a nudge every WATCH_MS.
        handler.postDelayed(warn, WATCH_MS);
        SharedPreferences prefs = service.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_KEY, service.getResources().getBoolean(R.bool.youtube_warning))) return;
        if (card != null) return;
        WindowManager wm = service.getSystemService(WindowManager.class);
        if (wm == null) return;

        float d = service.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(28 * d);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xF0B3261E);       // M3 error container, nearly opaque over video
        shape.setCornerRadius(24 * d);
        panel.setBackground(shape);

        TextView title = new TextView(service);
        title.setText(R.string.youtube_warning_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(service.getResources().getFont(R.font.sans_headline));
        panel.addView(title);
        TextView body = new TextView(service);
        body.setText(R.string.youtube_warning_body);
        body.setTextColor(0xFFFFE0DC);
        body.setTextSize(20);
        body.setTypeface(service.getResources().getFont(R.font.sans_regular));
        body.setPadding(0, Math.round(8 * d), 0, 0);
        panel.addView(body);

        // An accessibility overlay needs no SYSTEM_ALERT_WINDOW; not focusable, so the remote still
        // drives YouTube underneath.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(48 * d);
        try {
            wm.addView(panel, lp);
        } catch (RuntimeException e) {
            return;   // the service lost its window token; nothing to show this time
        }
        card = panel;
        handler.postDelayed(dismiss, SHOW_MS);
    }

    private void hide() {
        handler.removeCallbacks(dismiss);
        if (card == null) return;
        View shown = card;
        card = null;
        if (service == null) return;
        WindowManager wm = service.getSystemService(WindowManager.class);
        if (wm == null) return;
        try {
            wm.removeView(shown);
        } catch (RuntimeException ignored) {
            // already gone with the service's window token
        }
    }
}
