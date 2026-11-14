#!/usr/bin/env bash
# Flag the findings in Totum's crash/diagnostics reports, from the laptop, over ssh to the Pi.
#
#   triage.sh considered [since]                       reports since a date (default: 7 days ago), considered or not; 📦 = its state dump is still held
#   triage.sh findings <report-id>                     one report's findings
#   triage.sh state <report-id> [file]                 the report's state dump, gzipped (default ./<report-id>.json.gz)
#   triage.sh add <report-id> <state> <title> [fixed_in] [note]
#   triage.sh set <finding-id> <state> [fixed_in] [note]
#
# States: new triaged fixed wontfix noise. The server has no host port, so this goes through
# the container's own address on the Pi. A report's state dump is kept while the report is unconsidered
# and deleted the moment it has a verdict, so fetch it (or load it: tools/emulator/load-state.sh <report-id>) first.
set -euo pipefail

PI=${CRASHLOG_PI:-pi@333133333.xyz}

remote() { ssh "$PI" "$(printf '%q ' "$@")"; }

base() {
  local ip
  ip=$(remote docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' totum-crashlog)
  [ -n "$ip" ] || { echo "triage: could not find the totum-crashlog container on $PI" >&2; exit 1; }
  echo "http://$ip:9140"
}

call() {
  local method=$1 path=$2
  shift 2
  local args=()
  for pair in "$@"; do args+=(--data-urlencode "$pair"); done
  remote curl -sS -X "$method" -G "$(base)$path" "${args[@]}"
}

show_considered() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
for r in data["reports"]:
    mark = "✅" if r["considered"] else "⬜"
    box = "📦" if r.get("has_state") else "  "
    print("{} {} {}  {:11} {:10} {}".format(mark, box, r["id"], r["kind"] or "?", r["app_version"] or "?", r["state"]))
    for f in r["findings"]:
        version = " " + f["fixed_in"] if f["fixed_in"] else ""
        print("     #{} {}{}: {}".format(f["id"], f["state"], version, f["title"]))
held = sum(1 for r in data["reports"] if r.get("has_state"))
print("\n{} of {} not yet considered; {} state dump(s) held (📦)".format(data["unconsidered"], len(data["reports"]), held))
'
}

case "${1:-}" in
  considered)
    since=${2:-$(date -u -d '7 days ago' +%Y-%m-%d)}
    call GET /api/considered "since=$since" | show_considered
    ;;
  findings)
    call GET "/api/report/${2:?report id}/findings"
    echo
    ;;
  state)
    id=${2:?report id}
    out=${3:-$id.json.gz}
    call GET "/api/report/$id/state" > "$out"
    if [ "$(head -c2 "$out" | od -An -tx1 | tr -d ' \n')" != 1f8b ]; then
      cat "$out" >&2
      echo >&2
      echo "triage: no state dump saved for $id (see the reason above)" >&2
      exit 1
    fi
    echo "$out ($(wc -c < "$out") bytes)"
    ;;
  add)
    pairs=("state=${3:?state}" "title=${4:?title}")
    [ -n "${5:-}" ] && pairs+=("fixed_in=$5")
    [ -n "${6:-}" ] && pairs+=("note=$6")
    call POST "/api/report/${2:?report id}/findings" "${pairs[@]}"
    echo
    ;;
  set)
    pairs=("state=${3:?state}")
    [ -n "${4:-}" ] && pairs+=("fixed_in=$4")
    [ -n "${5:-}" ] && pairs+=("note=$5")
    call POST "/api/finding/${2:?finding id}" "${pairs[@]}"
    echo
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
