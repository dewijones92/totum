#!/usr/bin/env bash
# Copy the newest state dump from the Pi into ~/claude-tasks/state-dumps/ (mode 600) and say how old it is.
#
#   tools/state/pull-state.sh                    newest dump on the Pi
#   STATE_PI=user@host tools/state/pull-state.sh another Pi address
#   STATE_DUMPS_DIR=<dir> ...                    another destination
#   STATE_MAX_AGE_DAYS=3 ...                     when to warn that the dump is stale
#
# Dumps from debug builds (the emulator, version 0.1.0-dev) are skipped: they are not Dewi's phone.
#
# Read-only on the Pi (ls, stat and cat). Take a fresh dump on the phone first: Quick Settings tile
# "📦 Send my state" (or Settings -> Diagnostics -> Send my state to Claude), then run this (ADR 159).
# Prints the file's name, the app version it came from and its age, never anything from inside it.
set -euo pipefail

HOST=${STATE_PI:-pi@192.168.0.19}
REMOTE_DIR=${STATE_REMOTE_DIR:-/home/pi/crashlog-data/states}
OUT=${STATE_DUMPS_DIR:-$HOME/claude-tasks/state-dumps}
MAX_AGE_DAYS=${STATE_MAX_AGE_DAYS:-3}

say() { printf '%s %s\n' "$(date +%T)" "$*"; }
die() { say "pull-state: $*" >&2; exit 1; }

umask 077
mkdir -p "$OUT"
chmod 700 "$OUT"

SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST")

say "asking $HOST for its newest state dump under $REMOTE_DIR (read-only)"
newest=$("${SSH[@]}" "ls -t $REMOTE_DIR/*.json.gz $REMOTE_DIR/linked/*.json.gz 2>/dev/null | grep -v -e '-0.1.0-dev.json.gz' | head -1") \
  || die "could not reach $HOST over ssh"
[ -n "$newest" ] || die "the Pi holds no state dump under $REMOTE_DIR; take one on the phone first (tile: Send my state)"
name=$(basename "$newest")
case "$newest" in */linked/*) kind="linked to a report" ;; *) kind="sent by hand" ;; esac

dest="$OUT/$name"
if [ -s "$dest" ]; then
  say "already have $name locally, not fetching it again"
else
  tmp=$(mktemp "$OUT/.pull-XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  say "fetching $name ($kind)"
  "${SSH[@]}" "cat $newest" > "$tmp" || die "the copy failed"
  [ -s "$tmp" ] || die "the copy is empty (a zero-byte file means the transfer failed, not that the Pi had nothing)"
  mv "$tmp" "$dest"
  trap - EXIT
fi
chmod 600 "$dest"

STALE_DAYS=$MAX_AGE_DAYS python3 -I - "$dest" <<'PY'
import gzip, json, os, sys, time

path = sys.argv[1]
try:
    d = json.loads(gzip.open(path).read())
except Exception as e:
    sys.exit("pull-state: %s is not a readable state dump: %s" % (os.path.basename(path), e))
if d.get("kind") != "totum-state-dump":
    sys.exit("pull-state: %s is not a Totum state dump (kind=%r)" % (os.path.basename(path), d.get("kind")))
taken = d.get("takenAtMs")
if not isinstance(taken, (int, float)):
    sys.exit("pull-state: the dump has no takenAtMs, so its age is unknown")
age_s = max(0, time.time() - taken / 1000.0)
days, rest = divmod(int(age_s), 86400)
hours, rest = divmod(rest, 3600)
print("file:    %s (%d bytes, mode %s)" % (os.path.basename(path), os.path.getsize(path), oct(os.stat(path).st_mode & 0o777)[2:]))
print("version: %s" % d.get("appVersion"))
print("taken:   %s" % time.strftime("%Y-%m-%d %H:%M", time.localtime(taken / 1000.0)))
print("age:     %dd %dh %dm" % (days, hours, rest // 60))
limit = float(os.environ["STALE_DAYS"])
if age_s > limit * 86400:
    print("⚠️ STALE: this dump is older than %g day(s). Tap 📦 Send my state on the phone, then run this again." % limit, file=sys.stderr)
PY
