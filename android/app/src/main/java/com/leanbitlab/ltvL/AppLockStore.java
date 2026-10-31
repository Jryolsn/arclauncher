package com.omeda.arc;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * App lock state: which apps are locked, the PIN (salted SHA-256, never stored in clear), and which locked
 * app is open right now after its PIN was entered.
 */
final class AppLockStore {
    private static final String PREFS = "arc_app_lock";
    private static final String KEY_LOCKED = "locked_packages";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_RELOCK = "relock_minutes";
    /** Locked from the start (can be unlocked): from these the lock could be switched off or Arc removed. */
    private static final String[] PROTECTED_PACKAGES = {"com.android.tv.settings", "com.android.vending"};

    /** How long an app stays open after its PIN: until it is left (the default). */
    static final int RELOCK_ON_LEAVE = 0;
    /** ... or until the TV goes to sleep. Any positive value is minutes. */
    static final int RELOCK_ON_SLEEP = -1;

    /** Locked apps whose PIN was entered, and when (elapsed realtime ms). Kept in memory only. */
    private static final Map<String, Long> unlocked = new HashMap<>();

    private AppLockStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> lockedPackages(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_LOCKED, new HashSet<>()));
    }

    static boolean isLocked(Context context, String packageName) {
        return packageName != null && lockedPackages(context).contains(packageName);
    }

    static void setLocked(Context context, String packageName, boolean locked) {
        Set<String> set = lockedPackages(context);
        if (locked) set.add(packageName); else set.remove(packageName);
        prefs(context).edit().putStringSet(KEY_LOCKED, set).apply();
    }

    static boolean hasPin(Context context) {
        return prefs(context).contains(KEY_PIN_HASH);
    }

    static int relockMinutes(Context context) {
        return prefs(context).getInt(KEY_RELOCK, RELOCK_ON_LEAVE);
    }

    static void setRelockMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_RELOCK, minutes).apply();
        unlocked.clear();
    }

    static void setPin(Context context, String pin) {
        boolean first = !hasPin(context);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = hex(salt);
        prefs(context).edit()
                .putString(KEY_PIN_SALT, saltHex)
                .putString(KEY_PIN_HASH, hash(saltHex, pin))
                .apply();
        if (first) {
            for (String pkg : PROTECTED_PACKAGES) setLocked(context, pkg, true);
        }
    }

    static boolean checkPin(Context context, String pin) {
        SharedPreferences p = prefs(context);
        String salt = p.getString(KEY_PIN_SALT, null);
        String expected = p.getString(KEY_PIN_HASH, null);
        return salt != null && expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), hash(salt, pin).getBytes(StandardCharsets.UTF_8));
    }

    /** True if this locked app may show now, because its PIN was entered and that is still valid. */
    static synchronized boolean isUnlocked(Context context, String packageName) {
        Long at = unlocked.get(packageName);
        if (at == null) return false;
        int minutes = relockMinutes(context);
        if (minutes > 0 && android.os.SystemClock.elapsedRealtime() - at > minutes * 60_000L) {
            unlocked.remove(packageName);
            return false;
        }
        return true;
    }

    static synchronized void unlock(String packageName) {
        unlocked.put(packageName, android.os.SystemClock.elapsedRealtime());
    }

    /** Another app came to the front: in "until I leave the app" mode, everything locks again. */
    static synchronized void onAppLeft(Context context, String newForeground) {
        if (relockMinutes(context) != RELOCK_ON_LEAVE) return;
        unlocked.keySet().removeIf(pkg -> !pkg.equals(newForeground));
    }

    /** The TV went to sleep: lock everything again in every mode. */
    static synchronized void relockAll() {
        unlocked.clear();
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
