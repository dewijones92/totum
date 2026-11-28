#!/usr/bin/env bash
# A PC screen playing a home-only film from the home server's public link, the way it does at a friend's house. ADR 182.
#   public-screen.sh [seconds]            the phone stand-in sends the load as the phone now does ("direct")
#   MODE=phone public-screen.sh [seconds] the old load (no "direct"): the page rewrites the link onto its own origin and fails
# Runs the REAL `totum serve` from cli/build/install (build it first: ./gradlew :cli:installDist) against a fake Jellyfin,
# asks it for a public cast session exactly as the phone does, and has a headless Edge on Windows (a private profile,
# never Dewi's browser) open the REAL bundled screen.html from a stand-in for the phone's page server. The page and the
# gate are on different ports, so the manifest and every segment are cross-origin and CORS really applies.
# Needs: ffmpeg, python3, curl, Node + playwright-core + Edge in C:\edge-cdp (see tools/cast-bench/standin).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
seconds=${1:-14}
mode=${MODE:-direct}
work=${WORK:-$(mktemp -d)}
api=18770 cast=18771 gate=18773 jelly=18096 loop=18772 phone=18780
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
curl -fs "http://127.0.0.1:$api/health" >/dev/null || { echo "public-screen: totum serve did not come up, see $work/serve.log" >&2; exit 1; }

say "asking for a public cast session for a PC screen (no model, as the phone sends)"
answer=$(curl -fs -X POST "http://127.0.0.1:$api/cast-out/session" \
  -d "{\"mediaId\":\"jellyfin:$id\",\"uri\":\"https://totum.test/jellyfin/stream/$id.mp4\",\"durationMs\":12000}")
echo "$answer" | sed -E 's#(cast-out/[0-9a-f]{6})[0-9a-f]{58}#\1...#'
url=$(echo "$answer" | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')
session=$(echo "$answer" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')
local_url="http://localhost:$gate/${url#https://totum.test/}"

python3 -u "$here/phone-screen-standin.py" "$repo/core/cast/src/main/resources/screen.html" $phone "$local_url" "$mode" \
  >"$work/phone.log" 2>&1 &
pids+=($!)
sleep 1

say "opening the page in Edge, mode=$mode, for ${seconds}s"
cp "$here/drive-screen.js" /mnt/c/edge-cdp/drive-screen.js
(cd /mnt/c/edge-cdp && cmd.exe /c "node drive-screen.js http://localhost:$phone/screen#key=standin $seconds") 2>&1 \
  | tr -d '\r' | sed -E 's#(cast-out/[0-9a-f]{6})[0-9a-f]{58}#\1...#g' | while IFS= read -r line; do echo "$(date +%T) $line"; done

say "ending the cast, as the phone does"
curl -s -X DELETE "http://127.0.0.1:$api/cast-out/session?id=$session"; echo
say "what the phone stand-in saw (links shortened)"
cat "$work/phone.log"
say "what the home server logged about it (links shortened by the server itself)"
grep -E "cast-out|public" "$work/serve.log" | tail -15 || true
