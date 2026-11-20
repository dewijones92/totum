#!/usr/bin/env bash
# Show a photo on the PC screen end to end: the emulator serves the receiver page, a headless Edge on Windows opens it
# through adb forward, and the phone's photo viewer is driven with uiautomator to mirror a photo to that screen.
# Needs the photos the PhoneMedia instrumented tests leave in MediaStore, and Node + playwright-core in C:\edge-cdp.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ui=$here/../../emulator/ui.py
shots=${SHOTS:-/tmp/photo-screen-shots}
mkdir -p "$shots" /mnt/c/edge-cdp/shots
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
P=com.dewijones92.totum
adb shell am force-stop $P
adb logcat -c
adb shell am start -n $P/.MainActivity >/dev/null
sleep 8
adb forward tcp:8765 tcp:8765 >/dev/null
key=$(adb shell run-as $P cat shared_prefs/totum_screen.xml | sed -n 's/.*name="key">\([^<]*\)<.*/\1/p')
[ -n "$key" ] || { echo "photo: no screen key on the device yet" >&2; exit 1; }
cp "$here/drive-photo.js" /mnt/c/edge-cdp/drive-photo.js
(cd /mnt/c/edge-cdp && cmd.exe /c "node drive-photo.js http://localhost:8765/screen#key=$key 150") 2>&1 \
  | tr -d '\r' | while IFS= read -r line; do echo "$(date +%T) edge $line"; done &
driver=$!
for tick in $(seq 1 30); do
  adb logcat -d | grep -q 'said hello' && break
  echo "$(date +%T) photo: waiting for the page to say hello (tick $tick/30)"
  sleep 2
done
"$ui" tap "Library"
sleep 2
"$ui" tap "On this phone" --scroll
sleep 3
"$ui" tap "Photos"
sleep 3
adb exec-out screencap -p > "$shots/1-photo-grid.png"
"$ui" tap "totum-phone-big"
sleep 3
adb exec-out screencap -p > "$shots/2-viewer.png"
"$ui" tap "Show on a screen"
sleep 2
adb exec-out screencap -p > "$shots/3-screen-menu.png"
"$ui" tap "Show on PC"
sleep 6
adb exec-out screencap -p > "$shots/4-showing.png"
adb shell input swipe 900 1200 150 1200 300
sleep 6
adb exec-out screencap -p > "$shots/5-next.png"
"$ui" tap "Stop showing"
sleep 4
adb exec-out screencap -p > "$shots/6-stopped.png"
adb logcat -d | grep dewidebug | grep -iE "picture|show|screen" | cut -c32-260 | tail -40 > "$shots/phone-log.txt"
wait $driver
