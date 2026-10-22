#!/usr/bin/env bash
# Play what Totum would cast in a stand-in receiver (Shaka in a private headless Edge on Windows).
#   standin.sh [plan] [model] [url]   plan as drive-standin.js takes it; model makes Totum judge formats as that TV;
#                                     url serves that file (as the phone reaches it) instead of what is playing
# Needs: the app playing something on $ANDROID_SERIAL, and Node + playwright-core in C:\edge-cdp.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
plan=${1:-start,seek:300,seek:60,subs:en}
model=${2:-}
url=${3:-}
adb forward tcp:8765 tcp:8765 >/dev/null
for i in $(seq 1 60); do
  [ -n "$url" ] && break
  adb shell dumpsys media_session | grep -qE "state=PlaybackState \{state=(3|PLAYING)" && break
  [ "$i" = 60 ] && { echo "standin: the app is not playing anything" >&2; exit 1; }
  sleep 0.5
done
adb logcat -c
args=(-a com.dewijones92.totum.DEBUG_CAST_SERVE -p com.dewijones92.totum)
[ -n "$model" ] && args+=(--es model "'$model'")
[ -n "$url" ] && args+=(--es url "'$url'")
adb shell am broadcast "${args[@]}" >/dev/null
url=""
for _ in $(seq 1 20); do
  url=$(adb logcat -d | grep -o 'dewidebug STANDIN url=[^ ]*' | tail -1 | cut -d= -f2)
  [ -n "$url" ] && break
  sleep 0.5
done
case "$url" in
  http*) ;;
  *) echo "standin: no manifest from the app (got '${url:-nothing}')" >&2; exit 1 ;;
esac
local_url="http://localhost:8765/${url#http*://*/}"
echo "standin: $local_url plan=$plan model=${model:-phone}"
cp "$here/drive-standin.js" /mnt/c/edge-cdp/drive-standin.js
(cd /mnt/c/edge-cdp && cmd.exe /c "node drive-standin.js $local_url $plan") 2>&1 | tr -d '\r' | while IFS= read -r line; do echo "$(date +%T) $line"; done
