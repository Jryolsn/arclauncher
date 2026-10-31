package com.omeda.arc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.flutter.FlutterInjector;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.android.FlutterActivityLaunchConfigs;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodChannel;

/**
 * Arc's quick settings on top of the app that is open (lib/quick_panel.dart). A see-through activity rather
 * than an overlay window, so it uses Arc's real Flutter widgets and lies under the dim layer.
 *
 * Its Flutter engine is kept between openings (and warmed up early by the accessibility service), so the panel
 * appears at once.
 */
public class QuickPanelActivity extends FlutterActivity {
    private static final int REQUEST_PIN = 2002;

    private static FlutterEngine engine;
    private static QuickPanelActivity current;
    private MethodChannel.Result pendingPinResult;

    static boolean isShowing() {
        return current != null;
    }

    static void show(Context context) {
        context.startActivity(new Intent(context, QuickPanelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    static void hide() {
        if (current != null) current.close();
    }

    /** Starts the panel's engine ahead of time. */
    static FlutterEngine warmUp(Context context) {
        if (engine != null) return engine;
        Context app = context.getApplicationContext();
        engine = new FlutterEngine(app); // registers the plugins (shared_preferences) itself
        BinaryMessenger messenger = engine.getDartExecutor().getBinaryMessenger();

        new MethodChannel(messenger, "arc/quick_panel").setMethodCallHandler((call, result) -> {
            QuickPanelActivity panel = current;
            switch (call.method) {
                case "close" -> {
                    if (panel != null) panel.close();
                    result.success(null);
                }
                case "lockNow" -> {
                    if (panel != null) panel.close();
                    ArcAccessibilityService.lockNow();
                    result.success(null);
                }
                case "openLauncherSettings" -> {
                    if (panel != null) panel.close();
                    MainActivity.openSettingsOnResume = true;
                    app.startActivity(new Intent(app, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    result.success(null);
                }
                default -> result.notImplemented();
            }
        });

        // The part of Arc's own channel that the panel's widgets (brightness, app lock) use.
        new MethodChannel(messenger, "me.efesser.flauncher/method").setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "setDimLevel" -> result.success(DimOverlayService.apply(app, call.<Integer>argument("darkness")));
                case "canDrawOverlays" -> result.success(Settings.canDrawOverlays(app));
                case "appLockGetLocked" -> result.success(new ArrayList<>(AppLockStore.lockedPackages(app)));
                case "appLockHasPin" -> result.success(AppLockStore.hasPin(app));
                case "appLockGetType" -> result.success(AppLockStore.lockType(app));
                case "appLockIsActive" -> result.success(ArcAccessibilityService.isRunning());
                case "appLockGetRelockMinutes" -> result.success(AppLockStore.relockMinutes(app));
                case "appLockSetRelockMinutes" -> {
                    AppLockStore.setRelockMinutes(app, call.<Integer>argument("minutes"));
                    result.success(true);
                }
                case "appLockRequestPin" -> {
                    QuickPanelActivity panel = current;
                    if (panel == null) {
                        result.success(false);
                        return;
                    }
                    panel.requestPin(result, "set".equals(call.argument("mode")), call.argument("type"));
                }
                default -> result.notImplemented();
            }
        });

        String bundle = FlutterInjector.instance().flutterLoader().findAppBundlePath();
        engine.getDartExecutor().executeDartEntrypoint(new DartExecutor.DartEntrypoint(bundle, "package:flauncher/quick_panel.dart", "quickPanelMain"));
        return engine;
    }

    @Nullable
    @Override
    public FlutterEngine provideFlutterEngine(@NonNull Context context) {
        return warmUp(context);
    }

    @Override
    public boolean shouldDestroyEngineWithHost() {
        return false;
    }

    @NonNull
    @Override
    protected FlutterActivityLaunchConfigs.BackgroundMode getBackgroundMode() {
        return FlutterActivityLaunchConfigs.BackgroundMode.transparent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        // Fresh values and focus on the first row. Only here: coming back from the PIN screen keeps the state.
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), "arc/quick_panel").invokeMethod("shown", null);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (pendingPinResult != null) {
            pendingPinResult.success(false);
            pendingPinResult = null;
        }
        super.onDestroy();
    }

    /** Leaving the app behind for anything else closes the panel, so it never lingers. */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        close();
    }

    private void close() {
        if (current == this) current = null;
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void requestPin(MethodChannel.Result result, boolean set, @Nullable String type) {
        if (pendingPinResult != null) pendingPinResult.success(false);
        pendingPinResult = result;
        if (type == null) type = AppLockStore.lockType(this);
        startActivityForResult(new Intent(this, PinActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION) // not "leaving": the panel stays open
                .putExtra(PinActivity.EXTRA_MODE, set ? PinActivity.MODE_SET : PinActivity.MODE_VERIFY)
                .putExtra(PinActivity.EXTRA_TYPE, type), REQUEST_PIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PIN && pendingPinResult != null) {
            pendingPinResult.success(resultCode == Activity.RESULT_OK);
            pendingPinResult = null;
        }
    }
}
