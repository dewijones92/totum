#!/usr/bin/env bash
# Checks the one rule both pull-state.sh and load-state.sh share: the newest dump is never an emulator's own
# (version 0.1.0-dev). Run: tools/state/test-newest-state.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"

dir=$(mktemp -d)
mkdir -p "$dir/linked"
touch -d '3 hours ago' "$dir/20261120T100000-phone-0.1.938.json.gz"
touch -d '1 hour ago' "$dir/linked/20261120T120000-abc12345-0.1.0-dev.json.gz"
touch -d '2 hours ago' "$dir/linked/20261120T110000-def67890-0.1.937.json.gz"

got=$(bash -c "$(state_newest_command "$dir")")
want="$dir/linked/20261120T110000-def67890-0.1.937.json.gz"
[ "$got" = "$want" ] || { echo "FAIL: newest was '$got', wanted '$want'" >&2; exit 1; }

got=$(bash -c "$(state_newest_command "$dir/linked/nothing-here")")
[ -z "$got" ] || { echo "FAIL: an empty folder gave '$got'" >&2; exit 1; }

only_dev=$(mktemp -d)
touch "$only_dev/20261120T120000-abc12345-0.1.0-dev.json.gz"
got=$(bash -c "$(state_newest_command "$only_dev")")
[ -z "$got" ] || { echo "FAIL: only a dev dump exists but '$got' was chosen" >&2; exit 1; }

for script in pull-state.sh ../emulator/load-state.sh; do
  grep -q 'state_newest_command' "$HERE/$script" || { echo "FAIL: $script does not use the shared helper" >&2; exit 1; }
  if grep -q 'ls -t' "$HERE/$script"; then echo "FAIL: $script has its own copy of the newest-dump rule" >&2; exit 1; fi
done
echo "ok: the newest dump skips 0.1.0-dev, and both scripts use the one helper"
