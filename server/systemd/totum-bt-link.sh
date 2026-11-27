#!/bin/sh
set -u
set -f

out=${TOTUM_BT_LINK_DIR:-/var/lib/totum-fetch/home/.totum/bt-link}
every=${TOTUM_BT_LINK_EVERY:-15}
once=${TOTUM_BT_LINK_ONCE:-}
hci=${TOTUM_HCITOOL:-hcitool}
tag=totum-bt-link

say() { printf '%s: %s\n' "$tag" "$*"; }

mkdir -p "$out" || { say "cannot make $out"; exit 1; }
say "writing the Bluetooth link quality of every connected device to $out every ${every}s"

sample() {
    macs=$("$hci" con 2>/dev/null | awk '/ACL/ {print $3}')
    for mac in $macs; do
        rssi=$("$hci" rssi "$mac" 2>/dev/null | awk '/RSSI return value/ {print $4}')
        lq=$("$hci" lq "$mac" 2>/dev/null | awk '/Link quality/ {print $3}')
        tx=$("$hci" tpl "$mac" 2>/dev/null | awk '/transmit power level/ {print $NF}')
        file="$out/$(printf '%s' "$mac" | tr ':' '_').txt"
        printf 'at=%s rssi=%s lq=%s tx=%s\n' "$(date +%s)" "${rssi:-}" "${lq:-}" "${tx:-}" > "$file.tmp" &&
            mv "$file.tmp" "$file"
    done
}

while true; do
    sample
    [ -n "$once" ] && exit 0
    sleep "$every"
done
