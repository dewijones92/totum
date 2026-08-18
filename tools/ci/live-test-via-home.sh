#!/usr/bin/env bash
# Run the live-YouTube instrumented test through Dewi's home broadband.
#
# WHY: a GitHub runner is a datacentre IP and YouTube bot-checks it, so SabrPlaybackTest comes
# back `Unplayable` and skips. His Pi already runs a WireGuard server whose clients exit the
# home connection (dot-files vpn-stack, `wg-home`), so CI borrows it for this one test. Note the
# Pi's OTHER exits are useless here on purpose: the web proxy and `wg-vpn` both leave via
# PureVPN by design, and a commercial VPN IP is bot-checked at least as hard as a datacentre one.
#
# A file rather than inline YAML for two reasons: `android-emulator-runner` rewrites backslash
# line-continuations inside its `script:` (its own log shows `sh -c \yes | sdkmanager`), which
# silently turned a wrapped gradle command into a task named backslash; and this has to run
# INSIDE that action's script, because it kills the emulator the moment the script returns.
#
# Exit codes are deliberate:
#   0  the live tests RAN and passed, or the tunnel could not come up at all (a home connection is
#      not a build dependency, and CI should not claim the app is broken because a router rebooted)
#   1  the live tests ran and FAILED, or the peer can still reach the LAN
#
# A test FAILURE fails this script. That is a deliberate change of 2026-08-18, and it is the whole
# lesson of that day: YouTube stopped serving the app's streams, `LiveStreamPlaysToItsEndTest` hit
# exactly that, its own `assumeTrue` called it "an environment condition and not this defect", this
# script printed "test SKIPPED", and CI went green while nothing in the app would play. Dewi found
# out by using it. "YouTube refused us" was true as an excuse in August and is now the defect
# itself, so it has to be able to turn the build red.
set -uo pipefail

if [ -z "${WG_CI_CONF:-}" ]; then
  echo "[live-test] no tunnel configured — skipping the live YouTube test"
  exit 0
fi

sudo apt-get update -qq && sudo apt-get install -y -qq wireguard-tools || {
  echo "[live-test] could not install wireguard-tools — skipping"
  exit 0
}

printf '%s\n' "$WG_CI_CONF" | sudo tee /etc/wireguard/wg0.conf >/dev/null
sudo chmod 600 /etc/wireguard/wg0.conf

if ! sudo wg-quick up wg0; then
  echo "[live-test] tunnel would not come up — skipping (home connection is not a build dependency)"
  exit 0
fi
trap 'sudo wg-quick down wg0 >/dev/null 2>&1 || true' EXIT

# Never print the IP itself: this repo is PUBLIC, so its logs are. Compare and state a verdict.
EGRESS=$(curl -s --max-time 20 https://api.ipify.org || true)
if [ "$EGRESS" != "${WG_EXPECTED_EGRESS_IP:-}" ]; then
  echo "[live-test] egress is NOT the expected residential IP — tunnel is not carrying traffic; skipping"
  exit 0
fi
echo "[live-test] egress is the expected residential IP"

# Asserted, never assumed. `wg-home` peers normally get FULL LAN access and this key lives in a
# public repo's secrets; the Pi firewalls this peer to internet-only egress
# (vpn-stack/wg-home-init/10-ci-peer-lockdown.sh). If that has stopped working, everything else
# here is irrelevant and the run must go red.
for TARGET in 192.168.0.1 192.168.0.19; do
  if timeout 5 bash -c "cat < /dev/null > /dev/tcp/$TARGET/80" 2>/dev/null; then
    echo "[live-test] LAN host reachable through the CI peer — LOCKDOWN FAILED"
    exit 1
  fi
done
echo "[live-test] LAN unreachable from the CI peer, as intended"

# Every live test, for the same reason: they need a residential IP. SabrPlaybackTest proves SABR
# playback; LiveDownloadedVideoOfflineTest proves a real yt-dlp download plays with the radios off;
# LiveStreamPlaysToItsEndTest plays a real stream to its actual END, which the deterministic
# version cannot: a generated WAV carries explicit sample sizes, so the extractor never reads to
# the data source's end-of-input. See docs/todos/stalls-near-the-end-of-an-item.md.
#
# LiveSabrDownloadTest proves the app can FETCH a stream itself — the path that reaches the
# members-only videos yt-dlp is refused.
#
# :app only. A bare `connectedDebugAndroidTest` runs every module, and :core:database has no
# class by this name, so the filter matches nothing there and the runner reports
# `initializationError` — a real red build caused entirely by asking the wrong module.
# The same ONE list ci.yml excludes, so a test cannot be skipped there and unrun here.
# NOT `package=` plus `class=`: AndroidJUnitRunner INTERSECTS those rather than unioning them, and the
# combination ran a single test while silently skipping the rest — green, with no live coverage.
LIVE=$(grep -vE '^\s*(#|$)' "$(dirname "$0")/live-instrumented-tests.txt" | paste -sd,)
echo "running live instrumented tests: $LIVE"
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class="$LIVE"

INSTRUMENTED_STATUS=$?

# The FAST live tests: plain JVM, no emulator, seconds rather than minutes. These are the ones that
# fetch real bytes and insist on getting most of a real stream — the guard that was missing when
# YouTube capped every stream at its first megabyte and the suite stayed green.
echo "[live-test] running the JVM live tests (real bytes, real YouTube)"
./gradlew test -Ptotum.liveTests --no-daemon
JVM_STATUS=$?

# Say whether they actually RAN or merely skipped. Without this the log shows "BUILD SUCCESSFUL"
# either way, so the one question this whole tunnel exists to answer — did YouTube serve us? —
# could not be answered from the log at all.
# One XML per DEVICE, not per class — "TEST-<avd>-_app-.xml" — so match any of them rather
# than a filename carrying the class, which found nothing and said so.
RESULTS=$(find app/build/outputs/androidTest-results -name 'TEST-*.xml' 2>/dev/null | head -1)
if [ -z "$RESULTS" ]; then
  echo "[live-test] no instrumented result file found — cannot say whether those ran"
elif grep -q '<skipped' "$RESULTS"; then
  echo "[live-test] an instrumented live test SKIPPED — YouTube declined to serve this run"
else
  echo "[live-test] the instrumented live tests RAN for real against live YouTube"
fi

if [ "$INSTRUMENTED_STATUS" -ne 0 ] || [ "$JVM_STATUS" -ne 0 ]; then
  echo "[live-test] FAILED against live YouTube (instrumented=$INSTRUMENTED_STATUS jvm=$JVM_STATUS)."
  echo "[live-test] This means the app cannot fetch what it needs RIGHT NOW. It is not a flake to"
  echo "[live-test] wave through: on 2026-08-18 exactly this was reported as a skip and shipped."
  exit 1
fi
echo "[live-test] live YouTube served us, and the app could use what it sent"
