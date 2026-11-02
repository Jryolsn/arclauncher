package com.omeda.arc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VPN guard: chosen apps may only run while a VPN is up. Works with any VPN app, since it only asks Android
 * whether a VPN network exists for Arc. A VPN that tunnels selected apps only must therefore include Arc too,
 * otherwise Arc can't see it (and the guard errs on the safe side: it reports the VPN as off).
 *
 * Also looks up the public IP, country and provider seen through the tunnel, for the indicator.
 */
final class VpnGuard {
    private static final String PREFS = "arc_vpn_guard";
    private static final String KEY_APPS = "guarded_packages";
    private static final String KEY_MODE = "mode";
    private static final String KEY_VPN_APP = "vpn_app";

    /** The app is covered by a screen until the VPN is up, and stopped if the VPN drops. */
    static final String MODE_BLOCK = "block";
    /** Same screen, but with "Open anyway". */
    static final String MODE_WARN = "warn";

    private static final String IP_INFO_URL = "https://ipinfo.io/json";
    private static final long INFO_MAX_AGE_MS = 10 * 60_000;

    interface Listener {
        void onVpnChanged(boolean up);
    }

    private static final Set<Network> vpnNetworks = ConcurrentHashMap.newKeySet();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Apps opened with "Open anyway", until they are left. Memory only. */
    private static final Set<String> allowedAnyway = ConcurrentHashMap.newKeySet();
    private static boolean started;
    private static Context appContext;

    private static volatile Map<String, Object> info = new HashMap<>();
    private static volatile long infoAt;
    private static volatile boolean fetching;

    private VpnGuard() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Settings ----

    static Set<String> guardedPackages(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_APPS, new HashSet<>()));
    }

    static boolean isGuarded(Context context, String packageName) {
        return packageName != null && guardedPackages(context).contains(packageName);
    }

    static void setGuarded(Context context, String packageName, boolean guarded) {
        Set<String> set = guardedPackages(context);
        if (guarded) set.add(packageName); else set.remove(packageName);
        prefs(context).edit().putStringSet(KEY_APPS, set).apply();
    }

    static String mode(Context context) {
        return prefs(context).getString(KEY_MODE, MODE_BLOCK);
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, MODE_WARN.equals(mode) ? MODE_WARN : MODE_BLOCK).apply();
        allowedAnyway.clear();
    }

    /** The VPN app the user picked, or the only one installed, or null. */
    static String vpnApp(Context context) {
        if (canCheckApps(context) && isUp(context)) {
            vpnUidRanges(context);
            if (vpnOwnerPackage != null) return vpnOwnerPackage;
        }
        String chosen = prefs(context).getString(KEY_VPN_APP, null);
        if (chosen != null && isInstalled(context, chosen)) return chosen;
        List<String> apps = installedVpnApps(context);
        return apps.size() == 1 ? apps.get(0) : null;
    }

    static void setVpnApp(Context context, String packageName) {
        prefs(context).edit().putString(KEY_VPN_APP, packageName).apply();
    }

    /** Apps that contain a VPN service (CyberGhost, WireGuard, Proton ...). */
    static List<String> installedVpnApps(Context context) {
        List<String> result = new ArrayList<>();
        List<ResolveInfo> services = context.getPackageManager()
                .queryIntentServices(new Intent("android.net.VpnService"), 0);
        for (ResolveInfo info : services) {
            if (info.serviceInfo == null) continue;
            String pkg = info.serviceInfo.packageName;
            if (!result.contains(pkg) && !pkg.equals(context.getPackageName())) result.add(pkg);
        }
        return result;
    }

    static String label(Context context, String packageName) {
        if (packageName == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The app's icon as PNG, for Flutter. */
    static byte[] iconPng(Context context, String packageName) {
        try {
            android.graphics.drawable.Drawable d = context.getPackageManager().getApplicationIcon(packageName);
            int size = 96;
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** For the quick panel: the app on screen and whether it needs VPN, plus the VPN status. */
    static Map<String, Object> currentApp(Context context, String packageName) {
        Map<String, Object> map = new HashMap<>();
        map.put("vpn", status(context));
        if (packageName == null || packageName.equals(context.getPackageName())) return map;
        map.put("packageName", packageName);
        map.put("name", label(context, packageName));
        map.put("icon", iconPng(context, packageName));
        map.put("guarded", isGuarded(context, packageName));
        map.put("covered", covers(context, packageName));
        return map;
    }

    private static boolean isInstalled(Context context, String packageName) {
        return label(context, packageName) != null;
    }

    // ---- "Open anyway" ----

    static void allowAnyway(String packageName) {
        allowedAnyway.add(packageName);
    }

    static boolean isAllowedAnyway(String packageName) {
        return allowedAnyway.contains(packageName);
    }

    /** Another app came to the front: "Open anyway" only lasts while the app stays open. */
    static void onAppLeft(String newForeground) {
        allowedAnyway.removeIf(pkg -> !pkg.equals(newForeground));
    }

    /** True if the guard would stop this app now. */
    static boolean blocks(Context context, String packageName) {
        return isGuarded(context, packageName) && !isAllowedAnyway(packageName) && !isProtected(context, packageName);
    }

    /** A VPN is up and, when Arc can check it, this app's traffic goes through it. */
    static boolean isProtected(Context context, String packageName) {
        return isUp(context) && !Boolean.FALSE.equals(covers(context, packageName));
    }

    // ---- Which apps the VPN covers ----
    // Android tells an ordinary app only whether a VPN applies to the app itself. With the DUMP permission
    // (granted once over adb: pm grant <arc> android.permission.DUMP) Arc reads the VPN's UID ranges from
    // the connectivity service instead, and knows for every app.

    private static final long UIDS_MAX_AGE_MS = 3_000;
    private static volatile List<int[]> vpnUidRanges;
    private static volatile long vpnUidsAt;
    /** The app that runs the VPN, when Arc can read it. */
    private static volatile String vpnOwnerPackage;

    static boolean canCheckApps(Context context) {
        return context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED;
    }

    /** True/false when known, null when Arc can't tell (no DUMP permission) or no VPN is up. */
    static Boolean covers(Context context, String packageName) {
        if (packageName == null || !canCheckApps(context) || !isUp(context)) return null;
        List<int[]> ranges = vpnUidRanges(context);
        if (ranges == null) {
            android.util.Log.w("ArcVpn", "VPN app list not found for " + packageName);
            return null;
        }
        int uid;
        try {
            uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        for (int[] r : ranges) {
            if (uid >= r[0] && uid <= r[1]) return true;
        }
        return false;
    }

    /** UID ranges of all VPN networks, from "dumpsys connectivity". Null if it couldn't be read. */
    private static synchronized List<int[]> vpnUidRanges(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (vpnUidRanges != null && now - vpnUidsAt < UIDS_MAX_AGE_MS) return vpnUidRanges;
        // The service may finish writing after dumpService returns, so read again a little later if the
        // VPN line isn't there yet (seen on the first look after the panel opens).
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) SystemClock.sleep(80L * attempt);
            List<int[]> ranges = readVpnUidRanges(context);
            if (ranges != null) {
                vpnUidRanges = ranges;
                vpnUidsAt = now;
                return ranges;
            }
        }
        return null;
    }

    private static List<int[]> readVpnUidRanges(Context context) {
        java.io.File file = new java.io.File(context.getCacheDir(), "connectivity.txt");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                if (!android.os.Debug.dumpService("connectivity", out.getFD(), new String[0])) return null;
                out.getFD().sync();
            }
            List<int[]> ranges = new ArrayList<>();
            boolean sawVpn = false;
            // e.g. "Transports: WIFI|VPN ... Uids: <{10114-10114, 10116-10116}> ... OwnerUid: 10106"
            java.util.regex.Pattern vpnTransport = java.util.regex.Pattern.compile("Transports: [A-Z|]*VPN");
            java.util.regex.Pattern range = java.util.regex.Pattern.compile("(\\d+)-(\\d+)");
            java.util.regex.Pattern owner = java.util.regex.Pattern.compile("OwnerUid: (\\d+)");
            int ownerUid = -1;
            try (BufferedReader r = new BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.contains("NetworkAgentInfo") || !vpnTransport.matcher(line).find()) continue;
                    java.util.regex.Matcher o = owner.matcher(line);
                    if (o.find()) ownerUid = Integer.parseInt(o.group(1));
                    int at = line.indexOf("Uids: <");
                    if (at < 0) continue;
                    sawVpn = true;
                    int end = line.indexOf('>', at);
                    java.util.regex.Matcher m = range.matcher(line.substring(at, end < 0 ? line.length() : end));
                    while (m.find()) {
                        ranges.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
                    }
                }
            }
            if (!sawVpn) return null;
            String[] owners = ownerUid > 0 ? context.getPackageManager().getPackagesForUid(ownerUid) : null;
            vpnOwnerPackage = owners != null && owners.length > 0 ? owners[0] : null;
            return ranges;
        } catch (Exception e) {
            android.util.Log.w("ArcVpn", "Reading VPN apps failed: " + e);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // ---- VPN state ----

    /** Starts following VPN networks. Safe to call more than once. */
    static synchronized void start(Context context) {
        if (started) return;
        started = true;
        appContext = context.getApplicationContext();
        ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();
        cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean wasUp = !vpnNetworks.isEmpty();
                vpnNetworks.add(network);
                fetchInfo(true);
                if (!wasUp) notifyListeners(true);
            }

            @Override
            public void onLost(Network network) {
                vpnNetworks.remove(network);
                if (vpnNetworks.isEmpty()) {
                    info = new HashMap<>();
                    infoAt = 0;
                    notifyListeners(false);
                }
            }
        });
    }

    static boolean isUp(Context context) {
        start(context);
        if (!vpnNetworks.isEmpty()) return true;
        // The callback may not have reported yet right after start: ask directly.
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    static void addListener(Listener listener) {
        listeners.add(listener);
    }

    static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners(boolean up) {
        main.post(() -> {
            for (Listener l : listeners) l.onVpnChanged(up);
        });
    }

    // ---- What the tunnel looks like from outside ----

    /** Status for the indicator: up, ip, country, countryCode, city, org, vpnApp, vpnAppPackage. */
    static Map<String, Object> status(Context context) {
        boolean up = isUp(context);
        Map<String, Object> map = new HashMap<>(up ? info : new HashMap<>());
        map.put("up", up);
        String vpnApp = vpnApp(context);
        map.put("vpnAppPackage", vpnApp);
        map.put("vpnApp", label(context, vpnApp));
        map.put("canCheckApps", canCheckApps(context));
        if (up && SystemClock.elapsedRealtime() - infoAt > INFO_MAX_AGE_MS) fetchInfo(false);
        return map;
    }

    /** Looks up the public address through the VPN network itself, so it shows where the tunnel comes out. */
    static void fetchInfo(boolean force) {
        if (fetching || (!force && SystemClock.elapsedRealtime() - infoAt < INFO_MAX_AGE_MS)) return;
        Network network = vpnNetworks.isEmpty() ? null : vpnNetworks.iterator().next();
        if (network == null) return;
        fetching = true;
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) network.openConnection(new URL(IP_INFO_URL));
                c.setConnectTimeout(8_000);
                c.setReadTimeout(8_000);
                c.setRequestProperty("Accept", "application/json");
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) body.append(line);
                } finally {
                    c.disconnect();
                }
                JSONObject json = new JSONObject(body.toString());
                Map<String, Object> m = new HashMap<>();
                String cc = json.optString("country", "");
                m.put("ip", json.optString("ip", ""));
                m.put("countryCode", cc);
                m.put("country", cc.isEmpty() ? "" : new Locale("", cc).getDisplayCountry());
                m.put("city", json.optString("city", ""));
                m.put("org", json.optString("org", "").replaceFirst("^AS\\d+\\s*", ""));
                info = m;
                infoAt = SystemClock.elapsedRealtime();
                if (!vpnNetworks.isEmpty()) notifyListeners(true);
            } catch (Exception e) {
                android.util.Log.w("ArcVpn", "IP lookup failed: " + e);
            } finally {
                fetching = false;
            }
        });
    }
}
