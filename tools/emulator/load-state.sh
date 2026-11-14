#!/usr/bin/env bash
# Load a state dump from the phone into the project emulator, so a report can be reproduced with the
# phone's own queue, history, playlists, groups and settings.
#
#   tools/emulator/load-state.sh                 fetch the newest dump from the Pi (read-only ssh) and load it
#   tools/emulator/load-state.sh <report-id>     fetch the dump that came with that report (triage.sh state) and load it
#   tools/emulator/load-state.sh <dump.json.gz>  load a dump you already have
#   LOAD_STATE_APK=<apk> tools/emulator/load-state.sh ...   install that debug build first (adb install -r)
#
# A report's dump is kept only while the report is unconsidered (ADR 159), so load it before judging the report.
#
# Uses the app's own backup restore (merge: nothing on the emulator is removed, the queue is replaced) through the
# debug-only StateDebugReceiver, so no database is written by hand. The YouTube sign-in is checked before and after
# and is never touched. Stats, the play trail, downloads and Exsurge state are in the dump for reading but are not
# part of a restore. Queue auto-download is switched off on the emulator afterwards so a big queue is not fetched.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
AVD=totum-api35
PKG=com.dewijones92.totum
RECEIVER=$PKG/$PKG.diagnostics.StateDebugReceiver
WAIT_TICKS=${LOAD_STATE_TICKS:-24}

say() { printf '%s %s\n' "$(date +%T)" "$*"; }
die() { say "load-state: $*" >&2; exit 1; }

exec 9>/tmp/totum-emulator.lock
flock 9

pi() {
  if [ -n "${CRASHLOG_PI:-}" ]; then echo "$CRASHLOG_PI"; return; fi
  sed -n 's/^PI=\${CRASHLOG_PI:-\(.*\)}$/\1/p' "$HERE/../crashlog-server/triage.sh"
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dump=${1:-}

if [ -z "$dump" ]; then
  host=$(pi)
  [ -n "$host" ] || die "no Pi address: set CRASHLOG_PI"
  say "fetching the newest state dump from the Pi (read-only)"
  ssh "$host" 'f=$(ls -t /home/pi/crashlog-data/states/*.json.gz /home/pi/crashlog-data/states/linked/*.json.gz 2>/dev/null | head -1); [ -n "$f" ] && cat "$f"' \
    > "$work/dump.json.gz" || die "the Pi has no state dump yet (nothing under crashlog-data/states)"
  dump=$work/dump.json.gz
elif [ ! -e "$dump" ] && [[ $dump =~ ^[0-9]{8}T[0-9]{6}-[0-9a-f]{8}$ ]]; then
  say "fetching the state dump of report $dump from the Pi (read-only)"
  "$HERE/../crashlog-server/triage.sh" state "$dump" "$work/dump.json.gz" >/dev/null \
    || die "no state dump for report $dump (the message above says whether it is considered, which deletes the dump)"
  dump=$work/dump.json.gz
fi
[ -s "$dump" ] || die "$dump is empty or missing"

python3 -I - "$dump" <<'PY' || die "$dump is not a Totum state dump"
import gzip, json, sys
d = json.loads(gzip.open(sys.argv[1]).read())
assert d.get("kind") == "totum-state-dump", "wrong kind"
b = d["backup"]
print("dump: app %s, format %s, %d queued, %d history, %d playlists, %d subscriptions, %d groups, %d progress, %d settings" % (
    d.get("appVersion"), d.get("format"), len(b.get("queue", [])), len(b.get("history", [])), len(b.get("playlists", [])),
    len(b.get("subscriptions", [])), len(b.get("groups", [])), len(b.get("progress", [])), len(b.get("settings", {}))))
PY

if [ -z "${ANDROID_SERIAL:-}" ]; then
  for serial in $(adb devices | awk '/^emulator-[0-9]+\tdevice/ {print $1}'); do
    if [ "$(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ]; then ANDROID_SERIAL=$serial; fi
  done
fi
[ -n "${ANDROID_SERIAL:-}" ] || die "$AVD is not running (boot it first); refusing to pick another device"
export ANDROID_SERIAL
name=$(adb emu avd name | head -1 | tr -d '\r')
[ "$name" = "$AVD" ] || die "$ANDROID_SERIAL is $name, not $AVD; refusing to touch it"
say "target $ANDROID_SERIAL ($name)"

signin() { adb shell "run-as $PKG sh -c 'test -s shared_prefs/youtube_account.xml && wc -c < shared_prefs/youtube_account.xml || echo MISSING'" | tr -d '\r'; }
before=$(signin)
say "YouTube sign-in file before: $before bytes"

if [ -n "${LOAD_STATE_APK:-}" ]; then
  say "installing $LOAD_STATE_APK (adb install -r: data and sign-in are kept)"
  adb install -r "$LOAD_STATE_APK" | tail -1
fi
adb shell cmd package query-receivers --brief -a com.dewijones92.totum.DEBUG_LOAD_STATE | grep -q StateDebugReceiver \
  || die "the installed build has no StateDebugReceiver: another build was installed over this one. Install a debug build of this branch (LOAD_STATE_APK=app/build/outputs/apk/debug/app-debug.apk) and run again"

adb push -q "$dump" /data/local/tmp/totum-load-state.json.gz
adb shell "run-as $PKG cp /data/local/tmp/totum-load-state.json.gz files/load-state.json.gz"
adb shell rm -f /data/local/tmp/totum-load-state.json.gz
adb shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 3
adb logcat -c
adb shell am broadcast -a com.dewijones92.totum.DEBUG_LOAD_STATE -n "$RECEIVER" > /dev/null

for tick in $(seq 1 "$WAIT_TICKS"); do
  log=$(adb logcat -d | grep 'dewidebug load-state' || true)
  say "waiting for the restore (tick $tick/$WAIT_TICKS)"
  if grep -q 'load-state: restored\|load-state: .* nothing loaded\|load-state: files/' <<<"$log"; then break; fi
  sleep 5
done
printf '%s\n' "$log" | cut -c1-700
grep -q 'load-state: restored' <<<"$log" || die "the app did not report a restore; see the lines above (is the debug build installed?)"

adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" > /dev/null
say "app restarted so the restored queue is read"

after=$(signin)
say "YouTube sign-in file after: $after bytes"
[ "$before" != MISSING ] && [ "$after" != MISSING ] || die "the YouTube sign-in file is missing (before=$before after=$after)"
say "load-state: done"
