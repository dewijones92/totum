#!/bin/sh
# Recover TorrServer when its /torrents endpoint deadlocks.
#
# WHY THIS EXISTS. On 2026-08-05 TorrServer had been answering /echo, /settings, / and /viewed
# in ~5ms for two days while /torrents - the only endpoint that lists or adds a torrent - hung
# forever. `docker ps` said Up, nginx said 200, and torrent streaming in Totum was simply dead.
# The log showed why: the container had started at 07:31 on the 3rd while gluetun's DNS was not
# yet answering, every resolver timed out, and DHT bootstrapped with zero nodes ("getting
# starting nodes: nothing resolved"). Nothing recovers that but a recreate.
#
# WHY /torrents AND NOT /echo. This is the whole point. A probe that does not exercise the thing
# the service exists for is decoration - /echo answered 200 throughout, which is exactly what
# made the outage invisible. Verified before shipping: a plain GET to /torrents 404s (exit 1)
# while the POST below returns [] (exit 0), so --post-data really does post; and `-T 5` against
# a black hole fails at exactly 5.00s, so a hang is caught rather than waited out.
set -eu

PROJECT_DIR=/home/pi/code/totum/server/torrserver
STATE_DIR=/var/lib/torrserver-watchdog
FAILS_FILE="$STATE_DIR/consecutive-failures"
# Three strikes at the 2-minute timer is ~6 minutes, comfortably longer than a cold start, so a
# slow boot is never mistaken for a wedge.
THRESHOLD=3

log() { logger -t torrserver-watchdog -- "$1"; echo "torrserver-watchdog: $1"; }

mkdir -p "$STATE_DIR"
if [ ! -f "$FAILS_FILE" ]; then echo 0 > "$FAILS_FILE"; fi
fails=$(cat "$FAILS_FILE")

# Never act while gluetun is unhealthy. TorrServer lives in gluetun's network namespace, so a VPN
# reconnect makes every probe fail; recreating then would loop against a moving target and, worse,
# reattach to a namespace that is about to be replaced.
gluetun_health=$(docker inspect -f '{{.State.Health.Status}}' gluetun 2>/dev/null || echo missing)
if [ "$gluetun_health" != healthy ]; then
    log "gluetun is $gluetun_health, not healthy - skipping probe and resetting the counter"
    echo 0 > "$FAILS_FILE"
    exit 0
fi

# A torrserver left in a dead gluetun namespace answers this probe from inside and reports healthy
# while nginx gets 502 (three days, 18-21 Oct 2026), so re-attach orphans before probing.
/home/pi/code/dot-files/vpn-stack/reattach-passengers.sh

# A stopped container is docker's restart policy to handle, not ours; recreating underneath it
# would race that.
if [ "$(docker inspect -f '{{.State.Running}}' torrserver 2>/dev/null || echo false)" != true ]; then
    log "torrserver is not running - leaving it to the restart policy"
    echo 0 > "$FAILS_FILE"
    exit 0
fi

if docker exec torrserver sh -c \
        'wget -q -T 5 -O /dev/null --post-data="{\"action\":\"list\"}" http://127.0.0.1:8090/torrents' \
        >/dev/null 2>&1; then
    if [ "$fails" -gt 0 ]; then
        log "/torrents recovered on its own after $fails failed probe(s)"
    fi
    echo 0 > "$FAILS_FILE"
    exit 0
fi

fails=$((fails + 1))
echo "$fails" > "$FAILS_FILE"
log "/torrents probe FAILED ($fails/$THRESHOLD consecutive) - note /echo would still answer 200"

if [ "$fails" -lt "$THRESHOLD" ]; then
    exit 0
fi

# `up -d --force-recreate` and not `restart`: this is a separate compose project pinned to
# gluetun's container ID via network_mode, so a recreate is also what re-attaches it to the
# CURRENT namespace. See dot-files' CLAUDE.md, "Recreating gluetun ORPHANS every container in its
# netns".
log "recreating torrserver after $fails consecutive failures"
cd "$PROJECT_DIR"
docker compose up -d --force-recreate
echo 0 > "$FAILS_FILE"
log "recreate complete"
