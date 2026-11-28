#!/bin/sh
set -u
set -f

env_file=${TOTUM_FETCH_ENV:-/var/lib/totum-fetch/fetch.env}
fallback_dev=${TOTUM_ARP_DEV:-eth0}
ip_cmd=${TOTUM_IP:-ip}
tag=totum-tv-arp

say() { printf '%s: %s\n' "$tag" "$*"; }

if [ ! -r "$env_file" ]; then
    say "cannot read $env_file, so no ARP entry was set"
    exit 1
fi

targets=$(grep -E '^TOTUM_CAST_TARGETS=' "$env_file" | tail -n 1 | cut -d= -f2-)
targets=$(printf '%s' "$targets" | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'\$//")
if [ -z "$targets" ]; then
    say "TOTUM_CAST_TARGETS is not set in $env_file, nothing to pin"
    exit 0
fi

failed=0
pinned=0
skipped=0
old_ifs=$IFS
IFS=';'
for entry in $targets; do
    IFS=$old_ifs
    case "$entry" in
        *@*) ;;
        *)
            say "skipped '$entry': it has no @mac, so it is not wake-on-LAN-able"
            skipped=$((skipped + 1))
            IFS=';'
            continue
            ;;
    esac
    where=${entry#*=}
    addr=${where%%@*}
    mac=${where#*@}
    if ! printf '%s' "$addr" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
        say "skipped '$entry': '$addr' is not an IPv4 address"
        skipped=$((skipped + 1))
        IFS=';'
        continue
    fi
    if ! printf '%s' "$mac" | grep -Eqi '^([0-9a-f]{2}:){5}[0-9a-f]{2}$'; then
        say "skipped '$entry': '$mac' is not a MAC (aa:bb:cc:dd:ee:ff)"
        skipped=$((skipped + 1))
        IFS=';'
        continue
    fi
    dev=$("$ip_cmd" -4 route get "$addr" 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "dev") {print $(i + 1); exit}}')
    if [ -z "$dev" ]; then
        dev=$fallback_dev
        say "no route found to $addr, using $dev"
    fi
    if "$ip_cmd" neigh replace "$addr" lladdr "$mac" nud permanent dev "$dev"; then
        say "pinned $addr -> $mac on $dev"
        pinned=$((pinned + 1))
    else
        say "FAILED to pin $addr -> $mac on $dev"
        failed=$((failed + 1))
    fi
    IFS=';'
done
IFS=$old_ifs

say "done: $pinned pinned, $skipped skipped, $failed failed"
[ "$failed" -eq 0 ]
