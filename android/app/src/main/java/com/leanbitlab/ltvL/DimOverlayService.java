package com.omeda.arc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Kvällsläge: a black, half-transparent layer over everything on the screen, video included.
 *
 * A Chromecast sends its picture over HDMI and has no backlight to turn down, and its Android build
 * lacks "Extra dim", so drawing a dark layer on top is the only way to dim the whole picture.
 * The layer can't be focused or touched, so the remote keeps working in whatever app is open.
 * A foreground service keeps it alive while other apps are in front.
 */
public class DimOverlayService extends Service {
    static final String EXTRA_DARKNESS = "darkness";
    private static final String CHANNEL = "dim_overlay";
    /** Never darker than this, so the picture never goes fully black by mistake. */
    private static final int MAX_DARKNESS = 90;

    private View overlay;

    /** darkness 0 (off) to 90 percent. Returns false if the app may not draw over other apps. */
    static boolean apply(Context context, int darkness) {
        Intent intent = new Intent(context, DimOverlayService.class);
        if (darkness <= 0) {
            context.stopService(intent);
            return true;
        }
        if (!Settings.canDrawOverlays(context)) return false;
        intent.putExtra(EXTRA_DARKNESS, darkness);
        context.startForegroundService(intent);
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        int darkness = intent == null ? 0 : intent.getIntExtra(EXTRA_DARKNESS, 0);
        darkness = Math.max(0, Math.min(MAX_DARKNESS, darkness));
        if (darkness == 0 || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showOverlay(darkness);
        return START_NOT_STICKY;
    }

    private void showOverlay(int darkness) {
        int color = Color.argb(Math.round(darkness * 2.55f), 0, 0, 0);
        if (overlay != null) {
            overlay.setBackgroundColor(color);
            return;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // Android 12+ would block touches under a layer darker than 80 %, but a TV has no touch:
        // the remote's keys go to the focused window, which this layer never is.
        overlay = new View(this);
        overlay.setBackgroundColor(color);
        getSystemService(WindowManager.class).addView(overlay, params);
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kvällsläge", NotificationManager.IMPORTANCE_MIN));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Kvällsläge")
                .setContentText("Skärmen är nedtonad")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public void onDestroy() {
        if (overlay != null) {
            getSystemService(WindowManager.class).removeView(overlay);
            overlay = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
