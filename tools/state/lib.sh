#!/usr/bin/env bash
# Shared by tools/state/pull-state.sh and tools/emulator/load-state.sh (source it).
#
# state_newest_command [dir] prints the shell command that names the newest state dump under dir (the Pi's
# crashlog states folder by default, including linked/). Dumps from debug builds (version 0.1.0-dev, the
# emulator) are skipped: they are not Dewi's phone. Run it on the Pi over ssh, or with bash for a local folder.

STATE_REMOTE_DIR_DEFAULT=/home/pi/crashlog-data/states

state_newest_command() {
  local dir=${1:-$STATE_REMOTE_DIR_DEFAULT}
  printf "ls -t %s/*.json.gz %s/linked/*.json.gz 2>/dev/null | grep -v -e '-0.1.0-dev.json.gz' | head -1" "$dir" "$dir"
}
