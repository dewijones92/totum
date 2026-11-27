---
title: The nightly instrumented job was red every night from 15 to 27 November
kind: todo
status: four causes found and fixed 2026-11-27 (three proved red then green on totum-api35); the tunnel cause is a hypothesis, to be settled by the next nightly's heartbeat lines
area: ci
priority: high — Dewi, 2026-11-27: "if there are constantly red CIs, don't leave them unactioned"
updated: 2026-11-27
---

# The nightly instrumented job: red every night

The nightly (`ci.yml`, event `schedule`, about 11:00 UTC) ran **the same commit, `0a74a7a`, from 23 to 27 November**: no push
reached `main` in between, so the same faults came back unchanged. The workflow, `tools/ci` and the test and support
directories involved did not change between `0a74a7a` and the fix (`git diff --stat 0a74a7a HEAD`).

| Night | Run | What failed | Cause |
|---|---|---|---|
| 21, 22, 23, 26 Nov (and 17 Nov) | 44892319143, 45010512505, 45161031655, 45808959474, 44069453682 | `AnHourLongItemDoesNotRebufferTest` (2): `MediaController method is called from a wrong thread` | the test drove `PlaybackQueue` from the instrumentation thread; since 6 Nov (ADR 131) `PlaybackQueue.becameCurrent` calls `setPlayReason`, which touched the controller on the caller's thread |
| the same nights | the same runs | `SeveralSharedLinksAllLandTest`: `not every shared link reached the queue … Got: nothing in time` | `SendHomeActivity` (1 Nov) also takes `ACTION_SEND text/plain`, so the test's package-only share opened Android's chooser and nothing was shared |
| 25 Nov | 45605493946 | six tests in the non-live phase (`TorrentQueuePlaybackTest`, `StreamPlaysToItsEndTest`, `StalledStreamRecoveryTest` ×2, `PlayerMetadataTest`, `SilenceIsReallyCutTest`): `never started playing`, `play() produced NO state` | `OfflineQueuePlaybackTest` switched Wi-Fi off and on again 1.3 s apart; Wi-Fi never reconnected, `DeviceRadios.goOnline` gave up after 30 s printing to a stdout nobody reads, and every later test saw `offline=true` (the video even played as sound only: `listen=true`) |
| 15, 16, 18, 19, 20, 24, 27 Nov | 43700605662, 43844796092, 44285774717, 44507111923, 44717914989, 45396733163, 45988164099 | `The hosted runner lost communication with the server` | see below |

## The lost runner

On 24 and 27 November the log is the same twice. The live phase starts (`wg-quick up wg0`, egress checked), the tests run in
list order, and the output stops during `SeveralSharedLinksAllLandTest`, 25 min 19 s after the job started both times.
Two minutes later the step is cancelled; the runner then runs its `if: always()` steps, uploads the artifact and exits. It was
alive, and reachable again the moment the script's `EXIT` trap tore the tunnel down.

`wg-quick` logs `ip -4 rule add not fwmark 51820 table 51820`, `ip -4 route add 0.0.0.0/0 dev wg0 table 51820`: **everything
the runner sends leaves through Dewi's home broadband, including its own link to GitHub**, at an MTU of 8921 (wg-quick took
it from the runner's interface). That fits "blocks its network access" in GitHub's message; it is not proved. Nights 15 to 20
November lost the runner outright, with no post-steps, so there is no log to read.

What changed, all in `tools/ci/live-test-via-home.sh` and `ci.yml`:

- the tunnel MTU is 1380 (`WG_MTU`);
- GitHub's runner-service ranges (`api.github.com/meta`, `actions`, IPv4, prefixes of /8 or longer, `tools/ci/github_runner_routes.py`)
  are added to the main route table, which wg-quick's `suppress_prefixlength 0` rule lets win over the tunnel. This runs after the
  egress and LAN checks, so it cannot make them pass or fail;
- a heartbeat line every 30 s: tunnel bytes since the last, the age of the last handshake, whether `api.github.com` answers and
  how fast, load, free memory;
- `timeout` on both live gradle runs (25 and 15 minutes) and `timeout-minutes: 60` on the job, so a stall ends in a named
  state and not an hour of limbo.

**To settle it:** after the next nightly, read the heartbeat lines. If `github=` stays answered and the runner is lost anyway,
the tunnel was not the cause; if a run ends `STOPPED after 25m`, the heartbeat shows which side stopped moving.

## Still open

- `AnHourLongItemDoesNotRebufferTest` has not measured anything since 6 Nov, because it died at `playNow`. Its first real result
  is the nightly after this change: see [an-hour-long-item-stalls-early-in-ci](an-hour-long-item-stalls-early-in-ci.md).
- A nightly that tests the same commit for five days says nothing new. It is not changed here.
