package com.omeda.arc;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PIN screen for the app lock, built for a TV remote: a 3x4 keypad with a clear focus highlight, and the
 * remote's number keys work too. Four digits, checked as soon as the fourth is entered.
 *
 * MODE_GATE   a locked app came to the front: right PIN lets it show, Back goes to the home screen.
 * MODE_VERIFY Arc asks before changing the lock (unlocking an app, changing the PIN): returns RESULT_OK.
 * MODE_SET    choose a new PIN (the old one first, if there is one), entered twice: returns RESULT_OK.
 */
public class PinActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_PACKAGE = "package";
    /** MODE_GATE from Arc's own launch: start the app once the PIN is right. */
    static final String EXTRA_LAUNCH = "launch";
    static final int MODE_GATE = 0;
    static final int MODE_VERIFY = 1;
    static final int MODE_SET = 2;

    private static final int PIN_LENGTH = 4;
    private static final int MAX_TRIES = 5;
    private static final long WAIT_MS = 30_000;
    private static int failedTries;
    private static long blockedUntil;

    private int mode;
    private String packageName;
    private final StringBuilder entered = new StringBuilder();
    /** MODE_SET: 0 = old PIN, 1 = new PIN, 2 = new PIN again. */
    private int setStep;
    private String firstNewPin;

    private TextView titleView;
    private TextView dotsView;
    private TextView messageView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_VERIFY);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (mode == MODE_SET) setStep = AppLockStore.hasPin(this) ? 0 : 1;
        if (mode != MODE_SET && !AppLockStore.hasPin(this)) {
            // No PIN yet, so nothing can be locked: let it through.
            succeed();
            return;
        }
        setContentView(buildLayout());
        updateTitle();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mode = intent.getIntExtra(EXTRA_MODE, MODE_VERIFY);
        packageName = intent.getStringExtra(EXTRA_PACKAGE);
        entered.setLength(0);
        updateDots();
        updateTitle();
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(12, 14, 18));

        if (mode == MODE_GATE && packageName != null) {
            try {
                PackageManager pm = getPackageManager();
                Drawable icon = pm.getApplicationIcon(packageName);
                ImageView iconView = new ImageView(this);
                iconView.setImageDrawable(icon);
                root.addView(iconView, new LinearLayout.LayoutParams(dp(56), dp(56)));
            } catch (PackageManager.NameNotFoundException ignored) {
                // app gone; the title still says what is locked
            }
        }

        titleView = text(24, Color.WHITE);
        titleView.setPadding(0, dp(12), 0, dp(8));
        root.addView(titleView);

        dotsView = text(36, Color.WHITE);
        dotsView.setLetterSpacing(0.4f);
        root.addView(dotsView);

        messageView = text(16, Color.rgb(255, 170, 120));
        messageView.setPadding(0, dp(4), 0, dp(16));
        root.addView(messageView);

        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✕"}};
        Button first = null;
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                Button b = keyButton(key);
                if (first == null) first = b;
                row.addView(b);
            }
            root.addView(row);
        }
        updateDots();
        if (first != null) {
            Button focus = first;
            focus.post(focus::requestFocus);
        }
        return root;
    }

    private Button keyButton(String key) {
        Button b = new Button(this);
        b.setText(key);
        b.setTextSize(24);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setBackground(keyBackground());
        b.setOnClickListener(v -> {
            switch (key) {
                case "⌫" -> backspace();
                case "✕" -> cancel();
                default -> digit(key.charAt(0));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(64));
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /** Grey at rest, accent-filled with a white ring when focused, so the remote's position is obvious. */
    private Drawable keyBackground() {
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            digit((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            backspace();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    private void digit(char c) {
        long now = SystemClock.elapsedRealtime();
        if (now < blockedUntil) {
            messageView.setText("Too many tries. Wait " + ((blockedUntil - now) / 1000 + 1) + " s");
            return;
        }
        if (entered.length() >= PIN_LENGTH) return;
        entered.append(c);
        messageView.setText("");
        updateDots();
        if (entered.length() == PIN_LENGTH) handler.postDelayed(this::submit, 120);
    }

    private void backspace() {
        if (entered.length() > 0) entered.setLength(entered.length() - 1);
        updateDots();
    }

    private void submit() {
        String pin = entered.toString();
        entered.setLength(0);
        updateDots();
        if (mode == MODE_SET) {
            submitNewPin(pin);
            return;
        }
        if (AppLockStore.checkPin(this, pin)) {
            failedTries = 0;
            succeed();
        } else {
            wrongPin();
        }
    }

    private void submitNewPin(String pin) {
        switch (setStep) {
            case 0 -> {
                if (AppLockStore.checkPin(this, pin)) {
                    failedTries = 0;
                    setStep = 1;
                } else {
                    wrongPin();
                }
            }
            case 1 -> {
                firstNewPin = pin;
                setStep = 2;
            }
            default -> {
                if (pin.equals(firstNewPin)) {
                    AppLockStore.setPin(this, pin);
                    succeed();
                    return;
                }
                messageView.setText("The PINs didn't match. Try again.");
                setStep = 1;
            }
        }
        updateTitle();
    }

    private void wrongPin() {
        failedTries++;
        if (failedTries >= MAX_TRIES) {
            failedTries = 0;
            blockedUntil = SystemClock.elapsedRealtime() + WAIT_MS;
            messageView.setText("Too many tries. Wait 30 s");
        } else {
            messageView.setText("Wrong PIN");
        }
    }

    private void succeed() {
        if (mode == MODE_GATE && packageName != null) {
            AppLockStore.unlock(packageName);
            if (getIntent().getBooleanExtra(EXTRA_LAUNCH, false)) {
                Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
                if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
        setResult(RESULT_OK);
        finish();
    }

    private void cancel() {
        if (mode == MODE_GATE) {
            // Leave the locked app behind: back to the home screen.
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    private void updateTitle() {
        if (titleView == null) return;
        String title;
        if (mode == MODE_SET) {
            title = switch (setStep) {
                case 0 -> "Enter current PIN";
                case 1 -> "Choose a new 4-digit PIN";
                default -> "Enter the new PIN again";
            };
        } else if (mode == MODE_GATE) {
            title = appLabel() + " is locked";
        } else {
            title = "Enter PIN";
        }
        titleView.setText(title);
    }

    private String appLabel() {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return "This app";
        }
    }

    private void updateDots() {
        if (dotsView == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PIN_LENGTH; i++) sb.append(i < entered.length() ? '●' : '○');
        dotsView.setText(sb);
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
