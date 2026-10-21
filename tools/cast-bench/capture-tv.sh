#!/bin/bash
# Captures a Cast device's mDNS reply from the Pi (raw sniff, past its input firewall), rebuilds it as a plain
# multicast announcement (no question section, uncompressed names: GmsCore rejects the unicast-reply form) and
# starts the replayer inside the emulator, so Totum's Cast dialog lists that device.
#   capture-tv.sh [tv-ip]          (default 192.168.0.219, the LG)
set -euo pipefail
TV=${1:-192.168.0.219}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${TMPDIR:-/tmp}/cast-bench; mkdir -p "$OUT"
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
sed "s/TV_IP/$TV/" "$HERE/sniff.py" | ssh pi@333133333.xyz 'sudo python3 -u -' > "$OUT/reply.b64"
python3 "$HERE/announce.py" "$OUT/reply.b64" "$OUT/announce.bin"
NDK=$(ls -d "${ANDROID_HOME:-$HOME/code/android-sdk}"/ndk/* | tail -1)
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android35-clang" -O2 -o "$OUT/castbeacon" "$HERE/castbeacon.c"
adb root >/dev/null; sleep 2
adb push "$OUT/castbeacon" "$OUT/announce.bin" /data/local/tmp/ >/dev/null
adb shell 'pkill castbeacon; cd /data/local/tmp && chmod 755 castbeacon && (nohup ./castbeacon announce.bin > beacon.log 2>&1 </dev/null &)'
sleep 3; adb shell tail -2 /data/local/tmp/beacon.log
