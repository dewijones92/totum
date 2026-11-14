#!/usr/bin/env bash
# Flag the findings in Totum's crash/diagnostics reports, from the laptop, over ssh to the Pi.
#
#   triage.sh considered [since]                       reports since a date (default: 7 days ago), considered or not;
#                                                      📦 state dump still held, 📷 N screenshots, 📝 the reporter wrote a note
#   triage.sh show <report-id> [out-dir]               the note and key facts, screenshots saved to out-dir (default ./<report-id>), where the state dump is
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
    shots = "📷 {}".format(r["screenshots"]) if r.get("screenshots") else "    "
    note = "📝" if r.get("has_note") else "  "
    print("{} {} {} {} {}  {:11} {:10} {}".format(
        mark, box, shots, note, r["id"], r["kind"] or "?", r["app_version"] or "?", r["state"]))
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
  show)
    id=${2:?report id}
    out=${3:-./$id}
    brief=$(call GET "/api/report/$id/brief")
    printf '%s' "$brief" | python3 -c '
import json, sys
b = json.load(sys.stdin)
if "error" in b:
    sys.exit("triage: " + b["error"])
print("{} {}  {} {}  {} / Android {}".format("✅" if b["considered"] else "⬜", b["id"], b["kind"] or "?", b["app_version"] or "?", b["device"] or "?", b["android"] or "?"))
print("received {}  commit {}".format(b["received_at"][:19], (b["git_commit"] or "?")[:8]))
if b["exception"] or b["message"]:
    print("{}: {}".format(b["exception"] or "message", (b["message"] or "").splitlines()[0] if b["message"] else ""))
print("📝 " + (b["note"] if b["note"] else "(no note)"))
print("📷 {} screenshot(s)".format(len(b["screenshots"])))
if b["has_state"]:
    print("📦 state dump held, {} bytes: triage.sh state {}  or  tools/emulator/load-state.sh {}".format(b["state_bytes"], b["id"], b["id"]))
else:
    print("no state dump held (it was deleted when the report was considered, was dropped for the size budget, or never arrived)" if b["considered"] else "no state dump held (it never arrived, or was dropped for the size budget)")
print("{} finding(s); triage.sh findings {}".format(b["findings"], b["id"]))
'
    mkdir -p "$out"
    for n in $(printf '%s' "$brief" | python3 -c 'import json, sys; print(*[s["n"] for s in json.load(sys.stdin).get("screenshots", [])])'); do
      name=$(printf '%s' "$brief" | python3 -c 'import json, sys; print(next(s["name"] for s in json.load(sys.stdin)["screenshots"] if s["n"] == int(sys.argv[1])))' "$n")
      call GET "/api/report/$id/screenshot/$n" > "$out/$name"
      echo "   $out/$name ($(wc -c < "$out/$name") bytes)"
    done
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
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
