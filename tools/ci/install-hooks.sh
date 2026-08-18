#!/usr/bin/env bash
# Points git at the repo's committed hooks. One command, and it survives a fresh clone —
# unlike .git/hooks, which is per-clone and therefore always missing on the machine that needed it.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
echo "core.hooksPath -> .githooks"
echo "pre-push will now run tools/ci/preflight.py (bypass with --no-verify)"
