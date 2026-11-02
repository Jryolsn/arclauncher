package com.omeda.arc;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Two things that need to work on top of any app:
 *
 * 1. The dim layer. Drawn as an accessibility overlay, which Android does not hide over its
 *    own Settings screens (ordinary app overlays are hidden there) and which may be fully opaque.
 * 3. The app lock: whenever a locked app comes to the front, however it was opened (Arc, voice search,
 *    recents, a link, casting from a phone), its sound is paused and the PIN screen covers it.
 * 4. The VPN guard: an app that needs VPN is covered by VpnGateActivity while no VPN is up, and is
 *    paused and covered at once if the VPN drops while it is open.
 * 2. Opening the quick panel (QuickPanelActivity) by holding Home or with a spare remote button (the YouTube
 *    button by default), so brightness and the lock can be changed without leaving the app that is open.
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

    private static ArcAccessibilityService instance;

    /** Our own windows and system overlays that come and go on top of apps without "leaving" them. */
    private static final String PIN_SCREENS = "com.omeda.arc.Pin";
    private static final String QUICK_PANEL = "com.omeda.arc.QuickPanel";
    private static final String VPN_GATE = "com.omeda.arc.VpnGate";
    private static final String HOME_SCREEN = "com.omeda.arc.MainActivity";
    private static final long RECHECK_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String foreground;
    private String gatedPackage;
    private long gatedAt;
    private String vpnGatedPackage;
    private long vpnGatedAt;
    private View vpnToast;
    private final Runnable hideToast = this::hideVpnToast;
    private final VpnGuard.Listener vpnListener = up -> {
        if (up) return;
        if (foreground != null) checkVpn(foreground, true);
        stopGuardedInBackground();
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AppLockStore.relockAll();
        }
    };
    /** Timed unlocks run out while the app is still open, so check again now and then. */
    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            if (foreground != null && !checkVpn(foreground, false)) checkLock(foreground);
            handler.postDelayed(this, RECHECK_MS);
        }
    };

    private View dimView;

    /** Applies the dim layer if the service runs. Returns false when it isn't enabled. */
    static boolean applyDarkness(Context context, int darkness) {
        prefs(context).edit().putLong(KEY_DARKNESS, darkness).apply();
        if (instance == null) return false;
        instance.setDarkness(darkness);
        return true;
    }

    /** The app on screen under Arc's own windows, or null when the service isn't running. */
    static String foregroundPackage() {
        ArcAccessibilityService service = instance;
        return service == null ? null : service.foreground;
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
        handler.postDelayed(recheck, RECHECK_MS);
        VpnGuard.start(this);
        VpnGuard.addListener(vpnListener);
        // Get the quick panel's engine going once things have settled, so holding Home opens it at once.
        handler.postDelayed(() -> QuickPanelActivity.warmUp(this), 15_000);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(recheck);
        VpnGuard.removeListener(vpnListener);
        hideVpnToast();
        try {
            unregisterReceiver(screenOff);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        removeDim();
        hidePanel();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgName = event.getPackageName();
        if (pkgName == null) return;
        String pkg = pkgName.toString();
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        // The PIN screen itself, the status bar, keyboards and dialogs from "android" don't change the app.
        if (cls.startsWith(PIN_SCREENS) || cls.startsWith(QUICK_PANEL) || cls.startsWith(VPN_GATE) || pkg.equals("com.android.systemui") || pkg.equals("android")
                || cls.contains("InputMethod") || pkg.contains("inputmethod") || pkg.contains(".ime")) {
            return;
        }
        // Arc's own overlays (VPN note, dim layer, Flutter views) aren't an app change; only its home screen is.
        if (pkg.equals(getPackageName()) && !cls.startsWith(HOME_SCREEN)) return;
        boolean entered = !pkg.equals(foreground);
        if (entered) {
            foreground = pkg;
            AppLockStore.onAppLeft(this, pkg);
            VpnGuard.onAppLeft(pkg);
            hideVpnToast();
        }
        if (checkVpn(pkg, false)) return; // the PIN is asked for once the VPN is up
        if (entered && VpnGuard.isGuarded(this, pkg)) showVpnToast();
        checkLock(pkg);
    }

    /**
     * Covers an app that needs VPN while none is up. Returns true when it did. [dropped]: the VPN just went
     * down under the open app, so it is paused at once and, in block mode, its process is ended once it is
     * in the background.
     */
    private boolean checkVpn(String pkg, boolean dropped) {
        if (!VpnGuard.blocks(this, pkg)) return false;
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(vpnGatedPackage) && now - vpnGatedAt < 1_500) return true;
        vpnGatedPackage = pkg;
        vpnGatedAt = now;
        pauseMedia();
        hideVpnToast();
        startActivity(new Intent(this, VpnGateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(VpnGateActivity.EXTRA_PACKAGE, pkg)
                .putExtra(VpnGateActivity.EXTRA_DROPPED, dropped));
        if (VpnGuard.MODE_BLOCK.equals(VpnGuard.mode(this))) {
            handler.postDelayed(() -> {
                ActivityManager am = getSystemService(ActivityManager.class);
                am.killBackgroundProcesses(pkg);
            }, 1_000);
        }
        return true;
    }

    /**
     * The VPN dropped: in block mode, end every app that needs VPN and sits in the background, so a paused
     * player can't carry on over the open connection. Android won't end one that runs a foreground service.
     */
    private void stopGuardedInBackground() {
        if (!VpnGuard.MODE_BLOCK.equals(VpnGuard.mode(this))) return;
        ActivityManager am = getSystemService(ActivityManager.class);
        for (String pkg : VpnGuard.guardedPackages(this)) {
            if (!pkg.equals(foreground)) am.killBackgroundProcesses(pkg);
        }
    }

    /** A small note in the corner for a few seconds when an app that needs VPN opens: where the tunnel comes out. */
    private void showVpnToast() {
        java.util.Map<String, Object> status = VpnGuard.status(this);
        StringBuilder text = new StringBuilder("\uD83D\uDD12 VPN");
        Object vpnApp = status.get("vpnApp");
        if (vpnApp != null) text.append(" \u00B7 ").append(vpnApp);
        Object country = status.get("country");
        if (country != null && !country.toString().isEmpty()) text.append(" \u00B7 ").append(country);
        Object ip = status.get("ip");
        if (ip != null && !ip.toString().isEmpty()) text.append(" \u00B7 ").append(ip);

        hideVpnToast();
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(200, 20, 24, 30));
        bg.setCornerRadius(24);
        view.setBackground(bg);
        int pad = Math.round(10 * getResources().getDisplayMetrics().density);
        view.setPadding(pad * 2, pad, pad * 2, pad);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = pad * 3;
        params.y = pad * 3;
        vpnToast = view;
        getSystemService(WindowManager.class).addView(view, params);
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 4_000);
    }

    private void hideVpnToast() {
        if (vpnToast != null) {
            try {
                getSystemService(WindowManager.class).removeView(vpnToast);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
            vpnToast = null;
        }
    }

    private void checkLock(String pkg) {
        if (!AppLockStore.isLocked(this, pkg) || !AppLockStore.hasPin(this)
                || AppLockStore.isUnlocked(this, pkg)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(gatedPackage) && now - gatedAt < 1_500) return; // PIN screen already on its way
        gatedPackage = pkg;
        gatedAt = now;
        pauseMedia();
        startActivity(new Intent(this, PinGateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_GATE)
                .putExtra(PinActivity.EXTRA_PACKAGE, pkg));
    }

    /** Stops video and music in the app behind the PIN screen. */
    private void pauseMedia() {
        AudioManager audio = getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
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
        Log.d("ArcKeys", "key " + event.getKeyCode() + " action " + event.getAction() + " repeat " + event.getRepeatCount());
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) return onHomeKey(event);
        int quickKey = (int) prefs(this).getLong(KEY_QUICK_KEY, KeyEvent.KEYCODE_UNKNOWN);
        boolean isQuickKey = event.getKeyCode() == quickKey
                || (quickKey == KeyEvent.KEYCODE_UNKNOWN && isDefaultQuickKey(event.getKeyCode()));
        if (!isQuickKey) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (QuickPanelActivity.isShowing()) hidePanel(); else showPanel();
        }
        return true; // the button does nothing else while Arc owns it
    }

    private static final long LONG_PRESS_MS = 600;
    private boolean homeLongPressed;
    private final Runnable homeLongPress = () -> {
        homeLongPressed = true;
        showPanel();
    };

    /**
     * Holding Home opens the quick panel; a normal press still goes home (sent on release instead,
     * since we can't tell the two apart before then).
     */
    private boolean onHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                homeLongPressed = false;
                handler.postDelayed(homeLongPress, LONG_PRESS_MS);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeLongPress);
            if (!homeLongPressed) {
                hidePanel();
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }
        return true;
    }

    /** The Chromecast remote's YouTube button, which does nothing once Google's home screen is off. */
    private static boolean isDefaultQuickKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_3 || keyCode == KeyEvent.KEYCODE_EXPLORER;
    }

    /** Arc's quick settings (lib/quick_panel.dart), on top of the app that is open. */
    private void showPanel() {
        QuickPanelActivity.show(this);
    }

    private void hidePanel() {
        QuickPanelActivity.hide();
    }

    /** "Lock apps now" in the quick panel: everything locks again, including the app that is open. */
    static void lockNow() {
        AppLockStore.relockAll();
        ArcAccessibilityService service = instance;
        if (service != null && service.foreground != null) {
            service.handler.postDelayed(() -> service.checkLock(service.foreground), 300);
        }
    }
}
