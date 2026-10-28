#!/bin/bash
set -euo pipefail
[ $# -ge 3 ] || { echo "usage: ANDROID_SERIAL=emulator-5554 $0 <debug apk> <player|scroll> <out-dir> [label]" >&2; exit 2; }
apk=$1 scenario=$2 out=$3 label=${4:-$(basename "$apk" .apk)-$2}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator this project started}"
pkg=com.dewijones92.totum
mkdir -p "$out"
adb install -r "$apk" >/dev/null
adb shell am force-stop $pkg
adb shell monkey -p $pkg 1 >/dev/null 2>&1
sleep 8
urls="$(python3 -c "print(','.join('http://10.0.2.2:8766/perf?i=%d' % i for i in range(1, 1800)))")"
adb shell am broadcast -a $pkg.DEBUG_QUEUE_PLAY_ALL -p $pkg --es ids dQw4w9WgXcQ --es urls "$urls" >/dev/null
sleep 15
adb shell input tap 740 2270
sleep 3
trace=/data/misc/perfetto-traces/$label.pftrace
adb shell rm -f "$trace"
case $scenario in
  player)
    adb shell input tap 300 1958
    sleep 4
    adb shell perfetto -o "$trace" -t 15s -b 64mb sched gfx view >/dev/null 2>&1
    ;;
  scroll)
    adb shell perfetto -o "$trace" -t 16s -b 64mb sched gfx view >/dev/null 2>&1 &
    sleep 2
    for _ in 1 2 3 4 5 6; do adb shell input swipe 540 1700 540 500 120; sleep 0.8; done
    for _ in 1 2 3; do adb shell input swipe 540 600 540 1800 120; sleep 0.8; done
    wait
    ;;
  *) echo "scenario must be player or scroll" >&2; exit 2 ;;
esac
adb pull "$trace" "$out/" >/dev/null 2>&1
query=$(mktemp)
cat > "$query" <<'SQL'
WITH a AS (
  SELECT s.dur FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
  WHERE p.name = 'com.dewijones92.totum' AND t.tid = p.pid AND s.name = 'animation'
), run AS (
  SELECT SUM(ts.dur) AS d FROM thread_state ts JOIN thread t USING(utid) JOIN process p USING(upid)
  WHERE p.name = 'com.dewijones92.totum' AND t.tid = p.pid AND ts.state = 'Running'
)
SELECT (SELECT COUNT(*) FROM a) AS frames, (SELECT COUNT(*) FROM a WHERE dur > 16e6) AS over_16ms,
  (SELECT COUNT(*) FROM a WHERE dur > 50e6) AS over_50ms, (SELECT ROUND(MAX(dur)/1e6,1) FROM a) AS worst_ms,
  (SELECT ROUND(d/1e6) FROM run) AS main_thread_running_ms;
SQL
echo "$label"
"${TRACE_PROCESSOR:-trace_processor}" -q "$query" "$out/$label.pftrace" 2>/dev/null | tail -2
rm -f "$query"
