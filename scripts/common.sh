# Shared bits for the helper scripts: finds adb and the TV address (from ../tv.conf or $TV).
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$DIR/tv.conf" ] && . "$DIR/tv.conf"
TV=${TV:?"Set your TV's IP address in tv.conf (see tv.conf.example)"}
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
ADB=$(command -v adb || ls ~/.tvlauncher-tools/android-sdk/platform-tools/adb ~/Library/Android/sdk/platform-tools/adb 2>/dev/null | head -1)
[ -n "$ADB" ] || { echo "adb not found - run build-and-install.sh once first"; exit 1; }
"$ADB" connect "$TV" >/dev/null
s() { "$ADB" -s "$TV" shell "$@" </dev/null; }
