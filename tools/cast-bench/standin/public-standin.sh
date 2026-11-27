#!/usr/bin/env bash
# Plays what a friend's Chromecast would fetch from the home server over the internet, in the Shaka stand-in
# (a private headless Edge on Windows, never Dewi's own browser). ADR 182.
#   public-standin.sh [plan]       plan as drive-standin.js takes it (default start,seek:5)
# Runs the REAL `totum serve` from cli/build/install (build it first: ./gradlew :cli:installDist) against a fake Jellyfin
# (serve-ranges.py over a generated H.264/AAC film), asks it for a public cast session exactly as the phone does, then
# opens the link's manifest in Shaka. The page is about:blank, so the manifest and every segment are cross-origin and CORS
# really applies. TLS is nginx's job on the Pi and is not exercised here (the link is rewritten to plain http on loopback).
# Needs: ffmpeg, python3, curl, Node + playwright-core + Edge in C:\edge-cdp (see the WslEdgeDriver rig).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
plan=${1:-start,seek:5}
work=${WORK:-$(mktemp -d)}
api=18770 cast=18771 gate=18773 jelly=18096 loop=18772
id=0123456789abcdef0123456789abcdef
pids=()
trap 'for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done' EXIT
say() { echo "$(date +%T) $*"; }

mkdir -p "$work/jf/Videos/$id" "$work/data" "$work/downloads"
say "making a 12 s H.264/AAC film in $work"
ffmpeg -v error -y -f lavfi -i "testsrc=duration=12:size=640x360:rate=24" -f lavfi -i "sine=frequency=440:duration=12" \
  -c:v libx264 -pix_fmt yuv420p -profile:v baseline -c:a aac -shortest -f mp4 "$work/jf/Videos/$id/stream"

python3 -u "$here/../serve-ranges.py" "$work/jf" --port $jelly >"$work/jellyfin.log" 2>&1 &
pids+=($!)

TOTUM_DATA="$work/data" TOTUM_DOWNLOADS="$work/downloads" TOTUM_SOUNDS="$work/none" \
TOTUM_CAST_BASE="http://127.0.0.1:$cast" TOTUM_CAST_PORT=$cast TOTUM_LOOPBACK_PORT=$loop \
TOTUM_CAST_PUBLIC_BASE="https://totum.test/cast-out" TOTUM_CAST_PUBLIC_PORT=$gate \
TOTUM_JELLYFIN_URL="http://127.0.0.1:$jelly" TOTUM_JELLYFIN_KEY=standin-key \
  "$repo/cli/build/install/totum/bin/totum" serve --port=$api >"$work/serve.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do curl -fs "http://127.0.0.1:$api/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fs "http://127.0.0.1:$api/health" >/dev/null || { echo "public-standin: totum serve did not come up, see $work/serve.log" >&2; exit 1; }

say "asking for a public cast session, as the phone does"
answer=$(curl -fs -X POST "http://127.0.0.1:$api/cast-out/session" \
  -d "{\"mediaId\":\"jellyfin:$id\",\"uri\":\"https://totum.test/jellyfin/stream/$id.mp4\",\"model\":\"Chromecast Ultra\",\"durationMs\":12000}")
echo "$answer" | sed -E 's#(cast-out/[0-9a-f]{6})[0-9a-f]{58}#\1...#'
url=$(echo "$answer" | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')
session=$(echo "$answer" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')
local_url="http://localhost:$gate/${url#https://totum.test/}"

say "the gate refuses everything but the link"
for path in / /screen /lan-check /targets "/cast-out/$(printf '0%.0s' $(seq 64))/manifest.mpd"; do
  echo "  GET $path -> $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$gate$path")"
done

say "playing $local_url in Shaka, plan=$plan"
cp "$here/drive-standin.js" /mnt/c/edge-cdp/drive-standin.js
(cd /mnt/c/edge-cdp && cmd.exe /c "node drive-standin.js $local_url $plan") 2>&1 | tr -d '\r' | sed -E 's#(cast-out/[0-9a-f]{6})[0-9a-f]{58}#\1...#g' | while IFS= read -r line; do echo "$(date +%T) $line"; done

say "ending the cast, as the phone does"
curl -s -X DELETE "http://127.0.0.1:$api/cast-out/session?id=$session"; echo
echo "  GET the link after ending -> $(curl -s -o /dev/null -w '%{http_code}' "$local_url")"
say "what the home server logged about it (links shortened by the server itself)"
grep -E "cast-out|public" "$work/serve.log" | tail -25 || true
