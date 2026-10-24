#!/bin/sh
set -eu

QUERY=${QUERY:-ubuntu}
SOLVED_AFTER_S=30

log() { logger -t search-warmer -- "$1"; echo "search-warmer: $1"; }

if [ "$(docker inspect -f '{{.State.Running}}' prowlarr 2>/dev/null || echo false)" != true ]; then
    log "prowlarr is not running - nothing to warm"
    exit 0
fi

K=$(grep -oP '(?<=<ApiKey>)[^<]+' /var/lib/prowlarr/config/config.xml)
export K
IDS=$(docker exec -e K prowlarr sh -c 'printf "X-Api-Key: %s\n" "$K" | curl -s -m 20 -H @- http://localhost:9696/api/v1/indexer' \
    | python3 -c 'import json,sys
print(",".join(str(i["id"]) for i in json.load(sys.stdin) if i.get("enable") and any(t for t in i.get("tags", []))))')
if [ -z "$IDS" ]; then
    log "no enabled indexer goes through FlareSolverr - nothing to warm"
    exit 0
fi

for ID in $(echo "$IDS" | tr ',' ' '); do
    OUT=$(docker exec -e K -e QUERY="$QUERY" -e ID="$ID" prowlarr sh -c 'printf "X-Api-Key: %s\n" "$K" | curl -s -m 170 -o /dev/null \
        -w "%{http_code} %{time_total}" -H @- \
        "http://localhost:9696/api/v1/search?query=$QUERY&type=search&indexerIds=$ID"' || echo "000 0")
    CODE=${OUT%% *}
    SECS=${OUT#* }
    WHOLE=${SECS%.*}
    if [ "$CODE" != 200 ]; then
        log "indexer $ID answered $CODE after ${SECS}s - the next run tries again"
    elif [ "$WHOLE" -ge "$SOLVED_AFTER_S" ]; then
        log "indexer $ID took ${SECS}s: its Cloudflare clearance had lapsed and was solved again, so searches stay fast"
    else
        log "indexer $ID answered in ${SECS}s: its Cloudflare clearance is still held"
    fi
done
