#!/bin/bash
# Fast bench verbs, no screen taps:
#   bench.sh play <id,id,...>     queue and play YouTube ids
#   bench.sh cast [name]          start casting to the TV whose name matches (default webOS)
#   bench.sh stop                 stop casting
#   bench.sh tv                   the LG's receiver state
#   bench.sh snap <file>          Pi webcam still of the LG
#   bench.sh log [regex]          Totum's [cast] lines
TV=${TV:-192.168.0.219}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
P=com.dewijones92.totum
case "$1" in
  play) adb shell am broadcast -a $P.DEBUG_QUEUE_PLAY_ALL -p $P --es ids "$2" >/dev/null ;;
  cast) adb shell am broadcast -a $P.DEBUG_CAST -p $P --es name "'${2:-webOS}'" >/dev/null ;;
  stop) adb shell am broadcast -a $P.DEBUG_CAST_STOP -p $P >/dev/null ;;
  tv) ssh pi@333133333.xyz "~/.local/share/catt-venv/bin/catt -d $TV info" 2>&1 | grep -iE '^title|player_state|current_time|content_type' | tr '\n' ' '; echo ;;
  snap) ssh pi@333133333.xyz 'curl -s -o ~/tmp/snap.jpg http://127.0.0.1:8101/snapshot' && scp -q pi@333133333.xyz:/home/pi/tmp/snap.jpg "$2" ;;
  until) scp -q tools/cast-bench/until-playing.py pi@333133333.xyz:tmp/until-playing.py && ssh pi@333133333.xyz "~/.local/share/catt-venv/bin/python -u tmp/until-playing.py $TV ${2:-wait} ${3:-}" ;;
  log) adb logcat -d | grep dewidebug | grep -E "${2:-\[cast\]}" | grep -vE "GET audio/[0-9]" | cut -c32-240 | tail -${3:-12} ;;
esac
