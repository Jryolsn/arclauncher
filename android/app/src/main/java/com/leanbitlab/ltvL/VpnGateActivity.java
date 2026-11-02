package com.omeda.arc;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Covers an app that needs a VPN while no VPN is up. Leaves by itself as soon as the VPN comes up
 * (and starts the app, when Arc was the one opening it). Runs in its own task, like the PIN gate.
 */
public class VpnGateActivity extends Activity implements VpnGuard.Listener {
    static final String EXTRA_PACKAGE = "package";
    /** Opened from Arc: start the app once the VPN is up (or on "Open anyway"). */
    static final String EXTRA_LAUNCH = "launch";
    /** The VPN went down while the app was in use. */
    static final String EXTRA_DROPPED = "dropped";

    private String packageName;
    private TextView titleView;
    private TextView textView;
    private LinearLayout buttons;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(12, 14, 18));
        root.setPadding(dp(48), dp(24), dp(48), dp(24));

        ImageView icon = new ImageView(this);
        icon.setId(android.R.id.icon);
        root.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        titleView = text(24, Color.WHITE);
        titleView.setPadding(0, dp(12), 0, dp(8));
        root.addView(titleView);

        textView = text(16, Color.rgb(170, 176, 188));
        textView.setPadding(0, 0, 0, dp(20));
        root.addView(textView);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        root.addView(buttons);

        setContentView(root);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VpnGuard.addListener(this);
        if (VpnGuard.isProtected(this, packageName)) proceed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        VpnGuard.removeListener(this);
    }

    @Override
    public void onVpnChanged(boolean up) {
        if (up && VpnGuard.isProtected(this, packageName)) proceed();
    }

    private void show(Intent intent) {
        packageName = intent.getStringExtra(EXTRA_PACKAGE);
        boolean dropped = intent.getBooleanExtra(EXTRA_DROPPED, false);
        String app = appLabel(packageName);

        ImageView icon = findViewById(android.R.id.icon);
        try {
            Drawable d = getPackageManager().getApplicationIcon(packageName);
            icon.setImageDrawable(d);
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            icon.setImageDrawable(null);
        }

        String vpnApp = VpnGuard.vpnApp(this);
        String vpnName = vpnApp != null ? VpnGuard.label(this, vpnApp) : "your VPN";
        if (VpnGuard.isUp(this)) {
            // The VPN is up, but this app isn't one of the apps it tunnels.
            titleView.setText(app + " is not on the VPN");
            textView.setText("The VPN is on, but " + app + " isn't in " + vpnName
                    + "'s list of apps.\nAdd it there to continue.");
        } else {
            titleView.setText(dropped ? "VPN disconnected" : "VPN is off");
            textView.setText(dropped
                    ? app + " was stopped so that it can't connect without VPN.\nConnect " + vpnName + " to continue."
                    : app + " only runs with VPN. Connect " + vpnName + " first.");
        }

        buttons.removeAllViews();
        Button first = null;
        if (vpnApp != null) {
            first = button("Open " + vpnName, () -> openApp(vpnApp));
        }
        if (VpnGuard.MODE_WARN.equals(VpnGuard.mode(this))) {
            Button anyway = button("Open anyway", this::openAnyway);
            if (first == null) first = anyway;
        }
        Button back = button("Back", this::goHome);
        if (first == null) first = back;
        Button focus = first;
        focus.post(focus::requestFocus);
    }

    /** The VPN is up: get out of the way, and start the app if Arc was opening it. */
    private void proceed() {
        if (isFinishing()) return;
        if (getIntent().getBooleanExtra(EXTRA_LAUNCH, false)) openApp(packageName);
        finish();
    }

    private void openAnyway() {
        VpnGuard.allowAnyway(packageName);
        if (getIntent().getBooleanExtra(EXTRA_LAUNCH, false)) openApp(packageName);
        finish();
    }

    private void openApp(String pkg) {
        if (pkg == null) return;
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private String appLabel(String pkg) {
        String label = VpnGuard.label(this, pkg);
        return label != null ? label : "This app";
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setBackground(buttonBackground());
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(56));
        lp.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(b, lp);
        return b;
    }

    /** Same look as the PIN keys: grey at rest, accent with a white ring when focused. */
    private Drawable buttonBackground() {
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(Color.rgb(38, 42, 50));
        rest.setCornerRadius(dp(14));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.rgb(70, 120, 220));
        focused.setStroke(dp(3), Color.WHITE);
        focused.setCornerRadius(dp(14));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, rest);
        return states;
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
