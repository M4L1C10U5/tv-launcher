#!/bin/bash
# The TV's "Stay awake" setting. It must be off for the screen saver to work.
#   ./scripts/stay-awake.sh off    screen saver works (recommended)
#   ./scripts/stay-awake.sh on     screen never times out (how some TVs ship)
. "$(dirname "$0")/common.sh"
V=0; [ "$1" = on ] && V=3
s settings put global stay_on_while_plugged_in $V
echo "Stay awake: $(s settings get global stay_on_while_plugged_in) (0 = off)"
