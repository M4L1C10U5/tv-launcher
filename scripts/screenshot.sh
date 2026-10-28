#!/bin/bash
# Saves a screenshot of the TV to out/tv-screen.png
. "$(dirname "$0")/common.sh"
mkdir -p "$DIR/out"
s screencap -p /sdcard/s.png && "$ADB" -s "$TV" pull /sdcard/s.png "$DIR/out/tv-screen.png" >/dev/null && s rm /sdcard/s.png
echo "Saved $DIR/out/tv-screen.png"
