package com.omeda.arc;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Two things that need to work on top of any app:
 *
 * 1. The dim layer. Drawn as an accessibility overlay, which Android does not hide over its
 *    own Settings screens (ordinary app overlays are hidden there) and which may be fully opaque.
 * 2. A quick panel on a spare remote button (the YouTube button by default), so brightness can be changed
 *    without leaving the app that is open.
 *
 * Settings are shared with the Flutter side through its SharedPreferences file (keys start with "flutter.").
 */
public class ArcAccessibilityService extends AccessibilityService {
    static final String FLUTTER_PREFS = "FlutterSharedPreferences";
    static final String KEY_DIM_NOW = "flutter.dim_now_enabled";
    static final String KEY_DIM_NOW_BRIGHTNESS = "flutter.dim_now_brightness";
    /** Darkness last asked for by Arc (0 = off), so the layer comes back after a restart. */
    static final String KEY_DARKNESS = "flutter.native_dim_darkness";
    static final String KEY_QUICK_KEY = "flutter.quick_panel_keycode";

    /** Not darker than this, so the picture can never go fully black by mistake. */
    private static final int MAX_DARKNESS = 90;
    private static final int STEP = 5;

    private static ArcAccessibilityService instance;

    private View dimView;
    private View panel;
    private TextView valueText;
    private Button toggleButton;

    /** Applies the dim layer if the service runs. Returns false when it isn't enabled. */
    static boolean applyDarkness(Context context, int darkness) {
        prefs(context).edit().putLong(KEY_DARKNESS, darkness).apply();
        if (instance == null) return false;
        instance.setDarkness(darkness);
        return true;
    }

    static boolean isRunning() {
        return instance != null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // The accessibility layer replaces the ordinary overlay, which Settings would hide.
        stopService(new Intent(this, DimOverlayService.class));
        setDarkness((int) prefs(this).getLong(KEY_DARKNESS, 0));
    }

    @Override
    public boolean onUnbind(Intent intent) {
        removeDim();
        hidePanel();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---- Dim layer ----

    private void setDarkness(int darkness) {
        darkness = Math.max(0, Math.min(MAX_DARKNESS, darkness));
        if (darkness == 0) {
            removeDim();
            return;
        }
        int color = Color.argb(Math.round(darkness * 2.55f), 0, 0, 0);
        if (dimView != null) {
            dimView.setBackgroundColor(color);
            return;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        dimView = new View(this);
        dimView.setBackgroundColor(color);
        getSystemService(WindowManager.class).addView(dimView, params);
    }

    private void removeDim() {
        if (dimView != null) {
            getSystemService(WindowManager.class).removeView(dimView);
            dimView = null;
        }
    }

    // ---- Quick panel ----

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int quickKey = (int) prefs(this).getLong(KEY_QUICK_KEY, KeyEvent.KEYCODE_UNKNOWN);
        boolean isQuickKey = event.getKeyCode() == quickKey
                || (quickKey == KeyEvent.KEYCODE_UNKNOWN && isDefaultQuickKey(event.getKeyCode()));
        if (!isQuickKey) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (panel == null) showPanel(); else hidePanel();
        }
        return true; // the button does nothing else while Arc owns it
    }

    /** The Chromecast remote's YouTube button, which does nothing once Google's home screen is off. */
    private static boolean isDefaultQuickKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_3 || keyCode == KeyEvent.KEYCODE_EXPLORER;
    }

    private void showPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(240, 30, 34, 40));
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Brightness");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        box.addView(title);

        valueText = new TextView(this);
        valueText.setTextColor(Color.WHITE);
        valueText.setTextSize(18);
        valueText.setPadding(0, dp(8), 0, dp(12));
        box.addView(valueText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button darker = button("− Darker", v -> changeBrightness(-STEP));
        Button lighter = button("+ Brighter", v -> changeBrightness(+STEP));
        row.addView(darker);
        row.addView(lighter);
        box.addView(row);

        toggleButton = button("", v -> {
            SharedPreferences p = prefs(this);
            p.edit().putBoolean(KEY_DIM_NOW, !p.getBoolean(KEY_DIM_NOW, false)).apply();
            applyDimNow();
        });
        box.addView(toggleButton);
        box.addView(button("Close", v -> hidePanel()));

        box.setFocusable(true);
        box.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                hidePanel();
                return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(360),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(48);
        panel = box;
        getSystemService(WindowManager.class).addView(panel, params);
        refreshPanel();
        darker.requestFocus();
    }

    private void hidePanel() {
        if (panel != null) {
            getSystemService(WindowManager.class).removeView(panel);
            panel = null;
        }
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setOnClickListener(onClick);
        b.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) hidePanel();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(0, dp(4), dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** Using the panel always means "dim now": it turns dimming on and changes its level. */
    private void changeBrightness(int delta) {
        SharedPreferences p = prefs(this);
        int brightness = (int) p.getLong(KEY_DIM_NOW_BRIGHTNESS, 40);
        brightness = Math.max(100 - MAX_DARKNESS, Math.min(100, brightness + delta));
        p.edit().putLong(KEY_DIM_NOW_BRIGHTNESS, brightness).putBoolean(KEY_DIM_NOW, true).apply();
        applyDimNow();
    }

    private void applyDimNow() {
        SharedPreferences p = prefs(this);
        boolean on = p.getBoolean(KEY_DIM_NOW, false);
        int brightness = (int) p.getLong(KEY_DIM_NOW_BRIGHTNESS, 40);
        int darkness = on ? 100 - brightness : 0;
        p.edit().putLong(KEY_DARKNESS, darkness).apply();
        setDarkness(darkness);
        refreshPanel();
    }

    private void refreshPanel() {
        if (panel == null) return;
        SharedPreferences p = prefs(this);
        boolean on = p.getBoolean(KEY_DIM_NOW, false);
        int brightness = (int) p.getLong(KEY_DIM_NOW_BRIGHTNESS, 40);
        valueText.setText(on ? "Dimmed to " + brightness + " %" : "Full brightness");
        toggleButton.setText(on ? "Turn off dimming" : "Dim to " + brightness + " %");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
