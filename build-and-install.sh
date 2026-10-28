#!/bin/bash
# Builds the TV launcher on this Mac and installs it on the TV.
# First run downloads Java and the Android SDK if they're missing (~1 GB, one time only).
# Usage: ./build-and-install.sh            (TV address from tv.conf)
#        TV=192.168.1.50 ./build-and-install.sh
cd "$(dirname "$0")"
[ -f tv.conf ] && . ./tv.conf          # personal settings, not in git (copy tv.conf.example)
TV=${TV:?"Set your TV's IP address in tv.conf (see tv.conf.example) or run: TV=192.168.x.x ./build-and-install.sh"}
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
OUT=${OUT:-./out}                      # where the APK, log and screenshots go
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
LOG="$OUT/tv-build-log.txt"
TOOLS=~/.tvlauncher-tools
mkdir -p "$TOOLS"
exec > >(tee "$LOG") 2>&1
step() { echo; echo "=== $* ==="; }

step "1/5 Java"
JH=""
for c in "$JAVA_HOME" "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$(/usr/libexec/java_home -v 17+ 2>/dev/null)" "$TOOLS/jdk/Contents/Home"; do
  if [ -n "$c" ] && [ -x "$c/bin/java" ]; then
    v=$("$c/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    if [ "${v:-0}" -ge 17 ] 2>/dev/null; then JH="$c"; break; fi
  fi
done
if [ -z "$JH" ]; then
  echo "Downloading Java 17 (Temurin)..."
  ARCH=$(uname -m); [ "$ARCH" = arm64 ] && A=aarch64 || A=x64
  curl -fL -o "$TOOLS/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$A/jdk/hotspot/normal/eclipse" || { echo "FAILED: Java download"; exit 1; }
  rm -rf "$TOOLS/jdk" && mkdir -p "$TOOLS/jdk" && tar -xzf "$TOOLS/jdk.tar.gz" -C "$TOOLS/jdk" --strip-components=1
  JH="$TOOLS/jdk/Contents/Home"
fi
export JAVA_HOME="$JH"; echo "JAVA_HOME=$JAVA_HOME"

step "2/5 Android SDK"
SDK=""
for c in "$ANDROID_HOME" ~/Library/Android/sdk "$TOOLS/android-sdk"; do
  [ -n "$c" ] && [ -d "$c/platforms" -o -d "$c/cmdline-tools" ] && SDK="$c" && break
done
[ -z "$SDK" ] && SDK="$TOOLS/android-sdk"
SM=$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)
if [ -z "$SM" ]; then
  echo "Downloading Android command-line tools..."
  mkdir -p "$SDK/cmdline-tools"
  curl -fL -o "$TOOLS/cmdline.zip" "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip" || { echo "FAILED: SDK download"; exit 1; }
  rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
  unzip -q "$TOOLS/cmdline.zip" -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
fi
export ANDROID_HOME="$SDK"; echo "ANDROID_HOME=$SDK"
yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null 2>&1
[ -d "$SDK/platforms/android-34" ] || "$SM" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" "platform-tools" | tail -3
echo "sdk.dir=$SDK" > local.properties

step "Fonts (Inter - free font close to Apple's SF Pro)"
FD=app/src/main/assets/fonts; mkdir -p "$FD"
if [ ! -f "$FD/Inter-Regular.ttf" ]; then
  if curl -fsL -o "$TOOLS/inter.zip" "https://github.com/rsms/inter/releases/download/v4.0/Inter-4.0.zip"; then
    rm -rf "$TOOLS/inter" && mkdir -p "$TOOLS/inter" && unzip -q -o "$TOOLS/inter.zip" -d "$TOOLS/inter"
    for w in Regular Medium SemiBold Bold Light; do
      f=$(find "$TOOLS/inter" -name "Inter-$w.ttf" | head -1); [ -n "$f" ] && cp "$f" "$FD/"
    done
  else echo "Couldn't download Inter - using the TV's default font"; fi
fi
echo "Fonts: $(ls "$FD" 2>/dev/null | tr '\n' ' ')"

step "3/5 Build"
chmod +x gradlew
./gradlew assembleDebug --console=plain -q 2>&1 | grep -vE "^w: |warning:" 
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "FAILED: build - tell Claude done so it can read the log"; exit 1; }
cp "$APK" "$OUT/TvLauncher.apk"; echo "Built: $OUT/TvLauncher.apk ($(du -h "$APK" | cut -f1))"

step "4/5 Install on TV"
ADB=""
for c in "$(command -v adb)" "$SDK/platform-tools/adb" ~/Library/Android/sdk/platform-tools/adb ~/platform-tools/adb; do
  [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
done
"$ADB" connect $TV >/dev/null; sleep 1
[ "$("$ADB" -s $TV get-state 2>&1)" = device ] || { echo "FAILED: TV not connected (is it on, same Wi-Fi, Network debugging on?)"; exit 1; }
"$ADB" -s $TV install -r -i com.android.vending "$OUT/TvLauncher.apk" || "$ADB" -s $TV install -r "$OUT/TvLauncher.apk" || { echo "FAILED: install"; exit 1; }
echo "Installer: $("$ADB" -s $TV shell dumpsys package com.home.tvlauncher | grep -o "installerPackageName=[^ ]*" | head -1)"
"$ADB" -s $TV shell pm grant com.home.tvlauncher android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
"$ADB" -s $TV shell pm grant com.home.tvlauncher android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
"$ADB" -s $TV shell pm grant com.home.tvlauncher android.permission.READ_CALENDAR 2>/dev/null
"$ADB" -s $TV shell pm grant com.home.tvlauncher com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null
echo "Switching on remote-button control..."
# add our service to the TV's list without switching off any other accessibility services
CUR=$("$ADB" -s $TV shell settings get secure enabled_accessibility_services </dev/null | tr -d '\r')
SVC=com.home.tvlauncher/com.home.tvlauncher.KeyMapService
case "$CUR" in *"$SVC"*) NEW="$CUR" ;; ""|null) NEW="$SVC" ;; *) NEW="$CUR:$SVC" ;; esac
"$ADB" -s $TV shell settings put secure enabled_accessibility_services "$NEW"
"$ADB" -s $TV shell settings put secure accessibility_enabled 1
echo "Accessibility: $("$ADB" -s $TV shell settings get secure enabled_accessibility_services)"
echo "TV apps found: $("$ADB" -s $TV shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep / | tr -d ' \r' | cut -d/ -f1 | sort -u | tr '\n' ' ')"

step "5/5 Make it the Home screen"
"$ADB" -s $TV shell cmd package set-home-activity --user 0 com.home.tvlauncher/.MainActivity
# hide the stock Google TV home screen so it never comes back (undo: pm enable com.google.android.tvlauncher)
"$ADB" -s $TV shell pm disable-user --user 0 com.google.android.tvlauncher >/dev/null 2>&1
"$ADB" -s $TV shell input keyevent KEYCODE_HOME; sleep 4
echo "Home is now: $("$ADB" -s $TV shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
"$ADB" -s $TV shell screencap -p /sdcard/s.png && "$ADB" -s $TV pull /sdcard/s.png "$OUT/tv-screen.png" >/dev/null && echo "Screenshot: $OUT/tv-screen.png"
echo; echo "ALL DONE."
