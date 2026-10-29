#!/usr/bin/env bash
# Drive Totum's PC screen end to end from the laptop: the phone (or emulator) on $ANDROID_SERIAL serves the receiver
# page, a headless Edge on Windows opens it through adb forward, and the phone is told to play there.
#   screen.sh [seconds]   (something must already be playing on the device)
#   WATCH=1 screen.sh [seconds]   the page's own ▶️ Watch here moves playback, as from the private-area tile
# Needs Node + playwright-core in C:\edge-cdp (see tools/cast-bench/standin).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
seconds=${1:-60}
adb forward tcp:8765 tcp:8765 >/dev/null
key=$(adb shell run-as com.dewijones92.totum cat shared_prefs/totum_screen.xml | sed -n 's/.*name="key">\([^<]*\)<.*/\1/p')
[ -n "$key" ] || { echo "screen: no screen key on the device yet (is the app running?)" >&2; exit 1; }
adb logcat -c
mkdir -p /mnt/c/edge-cdp/shots
cp "$here/drive-screen.js" /mnt/c/edge-cdp/drive-screen.js
(cd /mnt/c/edge-cdp && cmd.exe /c "node drive-screen.js http://localhost:8765/screen#key=$key $seconds ${WATCH:+watch}") 2>&1 \
  | tr -d '\r' | while IFS= read -r line; do echo "$(date +%T) $line"; done &
driver=$!
for tick in $(seq 1 60); do
  adb logcat -d | grep -q 'said hello' && break
  echo "$(date +%T) screen: waiting for the page to say hello (tick $tick/60)"
  sleep 2
done
screen_id=$(adb logcat -d | sed -n 's/.*(\(screen-[0-9-]*\)) from .* said hello.*/\1/p' | tail -1)
[ -n "$screen_id" ] || { echo "screen: the page never said hello" >&2; kill $driver; exit 1; }
if [ -n "${WATCH:-}" ]; then
  echo "$(date +%T) screen: the page presses Watch here itself"
else
  echo "$(date +%T) screen: telling the phone to play on the PC"
  adb shell am broadcast -n com.dewijones92.totum/.queue.QueueDebugReceiver -a com.dewijones92.totum.DEBUG_PLAY_ON_SCREEN --es id "$screen_id" >/dev/null
fi
sleep 20
echo "$(date +%T) screen: pause from the phone"
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 6
echo "$(date +%T) screen: play from the phone"
adb shell input keyevent KEYCODE_MEDIA_PLAY
sleep 8
echo "$(date +%T) screen: back to the phone"
adb shell am broadcast -n com.dewijones92.totum/.queue.QueueDebugReceiver -a com.dewijones92.totum.DEBUG_PLAY_ON_SCREEN --ez back true >/dev/null
wait $driver
