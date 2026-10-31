package com.omeda.arc;

import android.animation.ObjectAnimator;
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
 * Lock screen for the app lock, built for a TV remote. Two kinds of secret:
 *
 * PIN            four digits, on a 3x4 keypad with a clear focus highlight (the remote's number keys work too).
 * Remote pattern 4-8 presses of up/down/left/right/OK. Each press only fills a dot with a short pulse, so
 *                someone watching the screen can see that something was pressed, never which direction.
 *
 * MODE_GATE   a locked app came to the front: the right secret lets it show, Back goes to the home screen.
 * MODE_VERIFY Arc asks before changing the lock: returns RESULT_OK.
 * MODE_SET    choose a new secret of EXTRA_TYPE (the current one first, if there is one), twice: RESULT_OK.
 */
public class PinActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_TYPE = "type";
    /** MODE_GATE from Arc's own launch: start the app once the secret is right. */
    static final String EXTRA_LAUNCH = "launch";
    static final int MODE_GATE = 0;
    static final int MODE_VERIFY = 1;
    static final int MODE_SET = 2;

    private static final int PIN_LENGTH = 4;
    private static final int PATTERN_MIN = 4;
    private static final int PATTERN_MAX = 8;
    /** While choosing a pattern, this long without a press ends it. */
    private static final long PATTERN_DONE_MS = 2_000;
    private static final int MAX_TRIES = 5;
    private static final long WAIT_MS = 30_000;
    private static int failedTries;
    private static long blockedUntil;

    private int mode;
    private String packageName;
    /** MODE_SET: the type being chosen. */
    private String newType;
    private final StringBuilder entered = new StringBuilder();
    /** MODE_SET: 0 = current secret, 1 = new secret, 2 = new secret again. */
    private int setStep;
    private String firstNewSecret;

    private TextView titleView;
    private LinearLayout dotsRow;
    private TextView hintView;
    private TextView messageView;
    private LinearLayout keypad;
    private Button firstKey;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishPattern = this::submit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readIntent(getIntent());
        if (mode != MODE_SET && !AppLockStore.hasPin(this)) {
            // No secret yet, so nothing can be locked: let it through.
            succeed();
            return;
        }
        setContentView(buildLayout());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        entered.setLength(0);
        refresh();
    }

    private void readIntent(Intent intent) {
        mode = intent.getIntExtra(EXTRA_MODE, MODE_VERIFY);
        packageName = intent.getStringExtra(EXTRA_PACKAGE);
        newType = intent.getStringExtra(EXTRA_TYPE);
        if (newType == null) newType = AppLockStore.TYPE_PIN;
        if (mode == MODE_SET) setStep = AppLockStore.hasPin(this) ? 0 : 1;
    }

    /** The kind of secret being typed right now. */
    private boolean patternInput() {
        String type = mode == MODE_SET && setStep > 0 ? newType : AppLockStore.lockType(this);
        return AppLockStore.TYPE_PATTERN.equals(type);
    }

    /** How many presses complete the current secret (0 = unknown, while choosing a pattern). */
    private int expectedLength() {
        if (!patternInput()) return PIN_LENGTH;
        if (mode == MODE_SET && setStep == 1) return 0;
        if (mode == MODE_SET && setStep == 2) return firstNewSecret.length();
        return AppLockStore.secretLength(this);
    }

    // ---- Layout ----

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(12, 14, 18));

        if (mode == MODE_GATE && packageName != null) {
            try {
                Drawable icon = getPackageManager().getApplicationIcon(packageName);
                ImageView iconView = new ImageView(this);
                iconView.setImageDrawable(icon);
                root.addView(iconView, new LinearLayout.LayoutParams(dp(56), dp(56)));
            } catch (PackageManager.NameNotFoundException ignored) {
                // app gone; the title still says what is locked
            }
        }

        titleView = text(24, Color.WHITE);
        titleView.setPadding(0, dp(12), 0, dp(12));
        root.addView(titleView);

        dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        dotsRow.setMinimumHeight(dp(28));
        root.addView(dotsRow);

        hintView = text(15, Color.rgb(150, 156, 168));
        hintView.setPadding(0, dp(10), 0, 0);
        root.addView(hintView);

        messageView = text(16, Color.rgb(255, 170, 120));
        messageView.setPadding(0, dp(6), 0, dp(14));
        root.addView(messageView);

        keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.CENTER);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✕"}};
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                Button b = keyButton(key);
                if (firstKey == null) firstKey = b;
                row.addView(b);
            }
            keypad.addView(row);
        }
        root.addView(keypad);
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
                default -> press(key.charAt(0));
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

    /** Title, hint, dots and which input shows, for the current mode and step. */
    private void refresh() {
        if (titleView == null) return;
        boolean pattern = patternInput();
        keypad.setVisibility(pattern ? View.GONE : View.VISIBLE);
        if (!pattern) firstKey.post(firstKey::requestFocus);

        String what = pattern ? "pattern" : "PIN";
        String title;
        if (mode == MODE_SET) {
            title = switch (setStep) {
                case 0 -> "Enter current " + (AppLockStore.TYPE_PATTERN.equals(AppLockStore.lockType(this)) ? "pattern" : "PIN");
                case 1 -> pattern ? "Choose a remote pattern" : "Choose a new 4-digit PIN";
                default -> "Enter the new " + what + " again";
            };
        } else if (mode == MODE_GATE) {
            title = appLabel() + " is locked";
        } else {
            title = "Enter " + what;
        }
        titleView.setText(title);
        if (pattern && mode == MODE_SET && setStep == 1) {
            hintView.setText("Press 4-8 of ↑ ↓ ← → OK, then wait 2 seconds");
        } else if (pattern) {
            hintView.setText("Use the arrows and OK");
        } else {
            hintView.setText("");
        }
        drawDots(false);
    }

    /** One dot per press. Never shows which key it was. */
    private void drawDots(boolean pulseLast) {
        dotsRow.removeAllViews();
        int expected = expectedLength();
        int count = Math.max(expected, entered.length());
        if (expected == 0) count = Math.max(PATTERN_MIN, entered.length());
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            boolean filled = i < entered.length();
            d.setColor(filled ? Color.WHITE : Color.TRANSPARENT);
            d.setStroke(dp(2), Color.WHITE);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
            lp.setMargins(dp(8), 0, dp(8), 0);
            dotsRow.addView(dot, lp);
            if (pulseLast && i == entered.length() - 1) {
                dot.setScaleX(1.6f);
                dot.setScaleY(1.6f);
                dot.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            }
        }
    }

    private void shake() {
        ObjectAnimator.ofFloat(dotsRow, View.TRANSLATION_X, 0, dp(14), -dp(14), dp(10), -dp(10), 0)
                .setDuration(320)
                .start();
    }

    // ---- Input ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (titleView != null && patternInput()) {
            char c = switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP -> 'U';
                case KeyEvent.KEYCODE_DPAD_DOWN -> 'D';
                case KeyEvent.KEYCODE_DPAD_LEFT -> 'L';
                case KeyEvent.KEYCODE_DPAD_RIGHT -> 'R';
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> 'O';
                default -> 0;
            };
            if (c != 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) press(c);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!patternInput() && keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            press((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
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

    private void press(char c) {
        long now = SystemClock.elapsedRealtime();
        if (now < blockedUntil) {
            messageView.setText("Too many tries. Wait " + ((blockedUntil - now) / 1000 + 1) + " s");
            return;
        }
        int expected = expectedLength();
        int max = expected > 0 ? expected : PATTERN_MAX;
        if (entered.length() >= max) return;
        entered.append(c);
        messageView.setText("");
        drawDots(true);
        handler.removeCallbacks(finishPattern);
        if (entered.length() == max) {
            handler.postDelayed(finishPattern, 150);
        } else if (expected == 0) {
            handler.postDelayed(finishPattern, PATTERN_DONE_MS);
        }
    }

    private void backspace() {
        if (entered.length() > 0) entered.setLength(entered.length() - 1);
        drawDots(false);
    }

    private void submit() {
        String secret = entered.toString();
        entered.setLength(0);
        if (mode == MODE_SET) {
            submitNew(secret);
        } else if (AppLockStore.checkPin(this, secret)) {
            failedTries = 0;
            succeed();
            return;
        } else {
            wrong();
        }
        drawDots(false);
    }

    private void submitNew(String secret) {
        switch (setStep) {
            case 0 -> {
                if (!AppLockStore.checkPin(this, secret)) {
                    wrong();
                    return;
                }
                failedTries = 0;
                setStep = 1;
            }
            case 1 -> {
                if (secret.length() < (patternInput() ? PATTERN_MIN : PIN_LENGTH)) {
                    messageView.setText("Use at least " + PATTERN_MIN + " presses");
                    shake();
                    return;
                }
                firstNewSecret = secret;
                setStep = 2;
            }
            default -> {
                if (secret.equals(firstNewSecret)) {
                    AppLockStore.setSecret(this, newType, secret);
                    succeed();
                    return;
                }
                messageView.setText("They didn't match. Try again.");
                shake();
                setStep = 1;
            }
        }
        refresh();
    }

    private void wrong() {
        shake();
        failedTries++;
        if (failedTries >= MAX_TRIES) {
            failedTries = 0;
            blockedUntil = SystemClock.elapsedRealtime() + WAIT_MS;
            messageView.setText("Too many tries. Wait 30 s");
        } else {
            messageView.setText(patternInput() ? "Wrong pattern" : "Wrong PIN");
        }
    }

    private void succeed() {
        handler.removeCallbacks(finishPattern);
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
        handler.removeCallbacks(finishPattern);
        if (mode == MODE_GATE) {
            // Leave the locked app behind: back to the home screen.
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    private String appLabel() {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return "This app";
        }
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
