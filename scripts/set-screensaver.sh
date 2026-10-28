#!/bin/bash
# Makes "Movie posters" the TV's screen saver and makes sure it actually starts when the TV is idle.
#   ./scripts/set-screensaver.sh          set it up
#   ./scripts/set-screensaver.sh --now    set it up and start it straight away
. "$(dirname "$0")/common.sh"
s settings put secure screensaver_enabled 1
s settings put secure screensaver_components com.home.tvlauncher/.PosterDream
s settings put secure screensaver_default_component com.home.tvlauncher/.PosterDream
# Some TVs (e.g. Skyworth) ship with "start screen saver on sleep" off, so they go black instead
s settings put secure screensaver_activate_on_sleep 1
s settings put secure screensaver_activate_on_dock 1
# ...and with "Stay awake" on, which stops the idle timer completely
s settings put global stay_on_while_plugged_in 0
echo "Screen saver: $(s settings get secure screensaver_components)"
echo "Starts after: $(( $(s settings get system screen_off_timeout | tr -d '\r') / 1000 )) seconds without a remote press"
echo "(change that on the TV: Settings > Device Preferences > Screen saver > When to start)"
if [ "$1" = --now ]; then
  s am start -n com.android.systemui/.Somnambulator 2>&1 | grep -qi error && s am start -n com.home.tvlauncher/.PosterPreviewActivity >/dev/null
fi
