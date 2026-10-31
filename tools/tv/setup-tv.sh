#!/usr/bin/env bash
# Sets up a Chromecast with Google TV the way ours is: debloated, Arc as the home screen, phone casting kept
# (Cast Fix), Arc's dimming / quick panel / app lock switched on. No root needed, everything over adb.
#
#   TV_SERIAL=<serial> tools/tv/setup-tv.sh            # all steps, asks before each one
#   TV_HOST=192.168.1.40 tools/tv/setup-tv.sh 4        # only step 4 (several: 4 5 6)
#   ... setup-tv.sh --pre                              # step 4 installs the newest pre-release of Arc
#
# Steps
#   1  snapshot     save the TV's package lists (for restore-tv.sh)
#   2  disable      disable the packages in lists/disable.txt
#   3  uninstall    remove the apps in lists/uninstall.txt
#   4  arc          install Arc from GitHub releases, allow its overlay, enable its accessibility service
#   5  castfix      install Cast Fix and pair it (you type the pairing code shown on the TV)
#   6  home         make Arc the home screen: turn off Google's home screen and setup wizard
#   7  reboot       restart and check that Home, Wi-Fi and casting came back
#
# Undo with tools/tv/restore-tv.sh. Tested on Chromecast with Google TV HD (boreal), Google TV 14.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

PRERELEASE=false
STEPS=()
for arg in "$@"; do
    case "$arg" in
        --pre) PRERELEASE=true ;;
        [1-7]) STEPS+=("$arg") ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done
[[ ${#STEPS[@]} -gt 0 ]] || STEPS=(1 2 3 4 5 6 7)
ASK=true
[[ $# -gt 0 && ${#STEPS[@]} -lt 7 ]] && ASK=false

ensure_connected
echo "TV: $(tv getprop ro.product.model) ($(tv getprop ro.product.device)), Android $(tv getprop ro.build.version.release), build $(tv getprop ro.build.id)"
[[ "$(tv getprop ro.product.device)" == boreal ]] || echo "Note: tested on boreal (Chromecast HD) only; check the lists before going on."

# Never touch these, whatever the lists say.
conflicts=$(comm -12 <({ read_list "$TOOLS/lists/disable.txt"; read_list "$TOOLS/lists/uninstall.txt"; } | sort -u) \
    <(read_list "$TOOLS/lists/never-touch.txt" | sort -u))
if [[ -n "$conflicts" ]]; then
    echo "STOP: these are in a list and in never-touch.txt:" >&2
    echo "$conflicts" >&2
    exit 1
fi

step_snapshot() {
    packages all >"$STATE/packages-all.txt"
    packages disabled >"$STATE/packages-disabled.txt"
    packages system >"$STATE/packages-system.txt"
    [[ -f "$STATE/disabled-before.txt" ]] || cp "$STATE/packages-disabled.txt" "$STATE/disabled-before.txt"
    [[ -f "$STATE/home-before.txt" ]] || home_activity >"$STATE/home-before.txt"
    echo "Saved to $STATE"
}

step_disable() {
    local all disabled
    all=$(packages all)
    disabled=$(packages disabled)
    while read -r pkg; do
        if ! grep -qx "$pkg" <<<"$all"; then echo "  not on TV   $pkg"; continue; fi
        if grep -qx "$pkg" <<<"$disabled"; then echo "  already off $pkg"; continue; fi
        echo "  $pkg: $(tv pm disable-user --user 0 "$pkg" 2>&1 || true)"
        echo "$pkg" >>"$STATE/disabled-by-setup.txt"
    done < <(read_list "$TOOLS/lists/disable.txt")
}

step_uninstall() {
    local all system todo=()
    all=$(packages all)
    system=$(packages system)
    while read -r pkg; do
        grep -qx "$pkg" <<<"$all" && todo+=("$pkg")
    done < <(read_list "$TOOLS/lists/uninstall.txt")
    if [[ ${#todo[@]} -eq 0 ]]; then echo "  nothing to remove"; return; fi
    printf '  will remove: %s\n' "${todo[@]}"
    if [[ "$ASK" == true ]] && ! confirm "Remove these?"; then return; fi
    for pkg in "${todo[@]}"; do
        if grep -qx "$pkg" <<<"$system"; then
            echo "  $pkg (system, for this user): $(tv pm uninstall --user 0 "$pkg" 2>&1 || true)"
            echo "$pkg" >>"$STATE/uninstalled-system.txt"
        else
            echo "  $pkg: $(adb -s "$TV" uninstall "$pkg" 2>&1 || true)"
        fi
    done
}

step_arc() {
    local apk
    apk="$(mktemp -d)/arc.apk"
    download_release_apk "$ARC_REPO" "$apk" "$PRERELEASE"
    adb -s "$TV" install -r "$apk"
    tv appops set "$ARC" SYSTEM_ALERT_WINDOW allow
    enable_accessibility "$ARC_A11Y"
    echo "  Arc installed. Overlay allowed, accessibility service on (dim screen, quick panel, app lock)."
    echo "  For updates: add https://github.com/$ARC_REPO to Obtainium (test TV: include pre-releases)."
}

enable_accessibility() {
    local current
    current=$(tv settings get secure enabled_accessibility_services)
    if [[ "$current" == *"$1"* ]]; then return; fi
    if [[ -z "$current" || "$current" == null ]]; then current="$1"; else current="$current:$1"; fi
    tv settings put secure enabled_accessibility_services "$current"
    tv settings put secure accessibility_enabled 1
}

step_castfix() {
    local apk port code
    if ! has_package "$CASTFIX"; then
        apk="$(mktemp -d)/castfix.apk"
        download_release_apk "$CASTFIX_REPO" "$apk" false
        adb -s "$TV" install -r "$apk"
    fi
    tv pm grant "$CASTFIX" android.permission.WRITE_SECURE_SETTINGS
    tv input keyevent KEYCODE_WAKEUP
    tv am start -n "$CASTFIX/.MainActivity" >/dev/null # once, so it receives boot events
    sleep 2
    tv settings put global development_settings_enabled 1
    tv settings put global adb_wifi_enabled 1
    tv am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null
    echo
    echo "  Pair Cast Fix: Developer options is now open on the TV. Go to Wireless debugging >"
    echo "  'Pair device with pairing code' and keep that dialog open. It shows a code and ip:port."
    read -r -p "  Pairing port (the number after the colon in the dialog, not the one on the screen before): " port </dev/tty
    read -r -p "  Six-digit pairing code: " code </dev/tty
    tv am broadcast -n "$CASTFIX/.PairReceiver" --ei port "$port" --es code "$code"
    echo "  Look for 'paired' above. Cast Fix then switches Wireless debugging off again after each run."
}

step_home() {
    if ! has_package "$ARC"; then echo "  Arc is not installed (step 4); not touching Home." >&2; return 1; fi
    if ! has_package "$CASTFIX"; then
        echo "  Cast Fix is not installed (step 5): without it phone casting stops working."
        [[ "$ASK" == true ]] && ! confirm "Go on anyway?" && return
    fi
    # The setup wizard owns Wi-Fi networks saved during first setup and deletes them when disabled.
    local owned
    owned=$(tv dumpsys wifi | grep -o "cname=$SETUP_WIZARD" | head -n 1 || true)
    if [[ -n "$owned" ]]; then
        echo "  Some saved Wi-Fi networks belong to the setup wizard and will be forgotten:"
        tv dumpsys wifi | grep -oE '(SSID: "[^"]+"|cname=[^ ,]+)' |
            awk -v w="cname=$SETUP_WIZARD" '/SSID/ {s=$0} $0==w && s {print "    " s; s=""}' | sort -u || true
        echo "  Connected now: $(tv cmd wifi status | grep -m1 -i 'connected to' || echo '?')"
        echo "  If the TV is on one of them now, forget it and connect again in Settings first, or adb drops."
        [[ "$ASK" == true ]] && ! confirm "Continue?" && return
    fi
    tv am start -n "$ARC_HOME" >/dev/null
    tv pm disable-user --user 0 "$STOCK_HOME"
    tv pm disable-user --user 0 "$SETUP_WIZARD"
    tv cmd package set-home-activity --user 0 "$ARC_HOME"
    echo "  Home is now: $(home_activity)"
}

step_reboot() {
    [[ "$ASK" == true ]] && ! confirm "Restart the TV now?" && return
    adb -s "$TV" reboot
    echo "  Waiting for the TV..."
    sleep 20
    [[ "$USE_WIFI" == true ]] && for _ in $(seq 1 20); do adb connect "$TV" >/dev/null 2>&1 && break; sleep 3; done
    adb -s "$TV" wait-for-device
    until [[ "$(tv getprop sys.boot_completed)" == 1 ]]; do sleep 2; done
    sleep 60 # Cast Fix needs about a minute after boot
    echo "  Home: $(home_activity)"
    echo "  Wi-Fi: $(tv cmd wifi status | grep -m1 -i 'connected to' || echo '?')"
    if tv logcat -d -s cast_shell:I | grep -q "Created singleton instance of ClientAuthSigner"; then
        echo "  Casting: ready"
    else
        echo "  Casting: not confirmed yet. Open Cast Fix on the TV, or try casting from the phone."
    fi
}

for step in "${STEPS[@]}"; do
    name=$(case "$step" in 1) echo snapshot ;; 2) echo disable ;; 3) echo uninstall ;; 4) echo arc ;;
        5) echo castfix ;; 6) echo home ;; 7) echo reboot ;; esac)
    echo
    echo "== Step $step: $name"
    if [[ "$ASK" == true && "$step" != 1 ]] && ! confirm "Run step $step ($name)?"; then continue; fi
    "step_$name"
done
echo
echo "Done. Undo with tools/tv/restore-tv.sh."
