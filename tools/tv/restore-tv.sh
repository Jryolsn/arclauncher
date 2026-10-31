#!/usr/bin/env bash
# Undoes setup-tv.sh: Google's home screen and setup wizard back on (Home and casting work as stock),
# disabled packages enabled again, removed system apps reinstalled. Apps removed for good (Netflix, Prime...)
# come back from the Play Store. Arc and Cast Fix stay installed; uninstall them by hand if wanted.
#
#   TV_SERIAL=<serial> tools/tv/restore-tv.sh          # everything
#   TV_SERIAL=<serial> tools/tv/restore-tv.sh home     # only the home screen
#
# If adb no longer works at all: Settings > Apps on the TV can enable apps too; last resort is a factory reset.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
ensure_connected
what="${1:-all}"

# Home first, so the Home button works as soon as possible.
echo "== Home screen"
tv pm enable --user 0 "$SETUP_WIZARD" || true
tv pm enable --user 0 "$STOCK_HOME" || true
tv cmd package set-home-activity --user 0 "$STOCK_HOME/.home.HomeActivity" ||
    echo "  (Android will ask which home screen to use on the next Home press)"
tv input keyevent KEYCODE_HOME
echo "  Home is now: $(home_activity)"
[[ "$what" == home ]] && exit 0

echo "== Disabled packages"
before="$STATE/disabled-before.txt"
disabled=$(packages disabled)
while read -r pkg; do
    grep -qx "$pkg" <<<"$disabled" || continue
    if [[ -f "$before" ]] && grep -qx "$pkg" "$before"; then
        echo "  leaving off  $pkg (was off before setup)"
        continue
    fi
    echo "  $pkg: $(tv pm enable --user 0 "$pkg" 2>&1 || true)"
done < <(read_list "$TOOLS/lists/disable.txt")

echo "== Removed system apps"
installed=$(packages all)
while read -r pkg; do
    grep -qx "$pkg" <<<"$installed" && continue
    result=$(tv cmd package install-existing --user 0 "$pkg" 2>&1 || true)
    case "$result" in *installed*) echo "  $pkg: back" ;; *) echo "  $pkg: not a system app (reinstall from Play Store)" ;; esac
done < <(read_list "$TOOLS/lists/uninstall.txt")

echo "Done. Press Home on the remote and check the home screen."
