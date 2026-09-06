#!/usr/bin/env bash
# Decrypt an app's HTTPS on the project emulator with mitmproxy: root, the mitm CA in the SYSTEM
# trust store (tmpfs over the conscrypt APEX, so no -writable-system and no reboot), global proxy
# to the WSL host. This is how SmartTube's /player was captured on 2026-09-06 and the TV timestamp
# scale found (docs/todos/tv-client-player-is-refused.md). Everything it does is undone by a reboot.
#
#   mitm-capture.sh on  [flows.mitm]   install the CA, set the proxy, start mitmdump in the background
#   mitm-capture.sh off                clear the proxy and stop mitmdump (the CA overlay stays until reboot)
#   mitm-capture.sh status
#
# Needs: ANDROID_SERIAL set to the emulator, `adb root` possible (google_apis / dev-keys image, not a
# Play image), mitmproxy installed in WSL. Read the flows afterwards with
#   mitmdump -nr flows.mitm -s <addon.py>   — use flow.request.content / response.content (decoded).
set -euo pipefail

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator (adb devices)}"
PORT=8080
HOST_FROM_EMULATOR=10.0.2.2
CA_PEM="$HOME/.mitmproxy/mitmproxy-ca-cert.pem"

on() {
  local flows="${1:-flows.mitm}"
  [ -f "$CA_PEM" ] || { echo "no $CA_PEM — run mitmdump once to generate the CA"; exit 1; }
  adb root >/dev/null; adb wait-for-device
  [ "$(adb shell id -u)" = 0 ] || { echo "adb root did not stick (Play image?)"; exit 1; }
  local hash; hash=$(openssl x509 -inform PEM -subject_hash_old -in "$CA_PEM" | head -1)
  adb push "$CA_PEM" "/data/local/tmp/$hash.0" >/dev/null
  adb shell "sh -s $hash" <<'REMOTE'
set -e
H=$1
if ! mount | grep -q ' /system/etc/security/cacerts type tmpfs'; then
  rm -rf /data/local/tmp/ca-copy; mkdir -m 700 /data/local/tmp/ca-copy
  cp /apex/com.android.conscrypt/cacerts/* /data/local/tmp/ca-copy/
  mount -t tmpfs tmpfs /system/etc/security/cacerts
  mv /data/local/tmp/ca-copy/* /system/etc/security/cacerts/
fi
cp /data/local/tmp/$H.0 /system/etc/security/cacerts/
chown root:root /system/etc/security/cacerts/*; chmod 644 /system/etc/security/cacerts/*
chcon u:object_r:system_file:s0 /system/etc/security/cacerts/*
Z=$(pidof zygote64 || pidof zygote)
for P in $Z $(ps -o PID -P $Z | grep -v PID); do
  nsenter --mount=/proc/$P/ns/mnt -- /bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts 2>/dev/null || true
done
echo "system store now holds $(ls /system/etc/security/cacerts | wc -l) certs incl. $H.0"
REMOTE
  adb shell settings put global http_proxy "$HOST_FROM_EMULATOR:$PORT"
  if ! ss -ltn | grep -q ":$PORT "; then
    nohup mitmdump --listen-host 0.0.0.0 -p "$PORT" -w "$flows" --set flow_detail=1 --set stream_large_bodies=5m \
      > "${flows%.mitm}.log" 2>&1 &
    sleep 2
  fi
  status
}

off() {
  adb shell settings put global http_proxy :0
  # A pattern that cannot match this shell's own command line — see memory pkill-f-self-match.
  pkill -f "mitmdump --listen-host 0.0.0.0 -p [$PORT]" || true
  status
}

status() {
  echo "proxy on device : $(adb shell settings get global http_proxy)"
  echo "mitmdump        : $(pgrep -af 'mitmdump --listen-host' | grep -v pgrep | cut -c1-90 || echo not running)"
  echo "CA overlay      : $(adb shell "mount | grep -c ' /system/etc/security/cacerts type tmpfs'") (1 = installed until reboot)"
}

case "${1:-}" in
  on) on "${2:-}";;
  off) off;;
  status) status;;
  *) sed -n 2,14p "$0"; exit 2;;
esac
