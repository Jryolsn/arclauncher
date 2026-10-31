# Shared helpers for the TV scripts. Sourced, not run.
# shellcheck disable=SC2034  # used by the scripts that source this file
#
# Pick the TV with TV_SERIAL (as `adb devices` shows it, e.g. over USB) or TV_HOST (Wi-Fi, e.g. 192.168.1.40
# or 192.168.1.40:44499).
if [[ -n "${TV_SERIAL:-}" ]]; then
    TV="$TV_SERIAL"
    USE_WIFI=false
else
    : "${TV_HOST:?Set TV_SERIAL (from adb devices) or TV_HOST (IP of the TV)}"
    case "$TV_HOST" in *:*) TV="$TV_HOST" ;; *) TV="$TV_HOST:5555" ;; esac
    USE_WIFI=true
fi

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARC="com.jryolsn.arc"
ARC_HOME="$ARC/com.omeda.arc.MainActivity"
ARC_A11Y="$ARC/com.omeda.arc.ArcAccessibilityService"
ARC_REPO="Jryolsn/arclauncher"
CASTFIX="com.thayford.castfix"
CASTFIX_REPO="thayford/castfix"
STOCK_HOME="com.google.android.apps.tv.launcherx"
SETUP_WIZARD="com.google.android.tungsten.setupwraith"

tv() {
    # </dev/null: otherwise adb eats the rest of a while-read loop's input.
    adb -s "$TV" shell "$@" </dev/null | tr -d '\r'
}

ensure_connected() {
    [[ "$USE_WIFI" == true ]] && { adb connect "$TV" >/dev/null 2>&1 || true; }
    if ! adb -s "$TV" get-state >/dev/null 2>&1; then
        echo "Can't reach $TV. Run 'adb connect $TV' (or plug in USB) and allow it on the TV." >&2
        exit 1
    fi
    STATE="$TOOLS/state/$(tv getprop ro.serialno)"
    mkdir -p "$STATE"
}

# Package names in a list file, without comments and blank lines.
read_list() {
    sed -e 's/#.*//' -e 's/[[:space:]]//g' "$1" | grep -v '^$' || true
}

# Package names, one per line: all | disabled | enabled | system | user
packages() {
    local flag=""
    case "$1" in
        disabled) flag="-d" ;;
        enabled) flag="-e" ;;
        system) flag="-s" ;;
        user) flag="-3" ;;
    esac
    tv pm list packages $flag | sed 's/^package://' | sort
}

has_package() { packages all | grep -qx "$1"; }

home_activity() {
    tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1
}

confirm() {
    local answer
    read -r -p "$1 [y/N] " answer </dev/tty
    [[ "$answer" == y || "$answer" == Y ]]
}

# Downloads the newest APK asset of a GitHub repo's releases to $2. $3=true includes pre-releases.
download_release_apk() {
    local repo="$1" out="$2" pre="${3:-false}" url
    url=$(curl -fsSL "https://api.github.com/repos/$repo/releases?per_page=20" | python3 "$TOOLS/newest_apk.py" "$pre")
    [[ -n "$url" ]] || { echo "No APK found in $repo releases." >&2; return 1; }
    echo "Downloading $url"
    curl -fsSL -o "$out" "$url"
}
