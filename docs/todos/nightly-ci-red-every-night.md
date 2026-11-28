---
title: The nightly instrumented job was red every night from 15 to 27 November
kind: todo
status: fixed; the nightly was fully green with the live phase running on 2026-11-28 (run 46083657361, v0.1.973), all 15 live suites passing (4K skips by its own assumption on the emulator)
area: ci
priority: high — Dewi, 2026-11-27: "if there are constantly red CIs, don't leave them unactioned"
updated: 2026-11-28
---

**Outcome (2026-11-27/28), three dispatched runs:**
- **46071703184 (v0.1.966): green, but hollow.** The live phase skipped itself with `[live-test] egress check got NO answer through the
  tunnel`. On the Pi, `wg-home` had been running since the 27 Nov reboot attached to **no network** (`Networks: {}`, so there was no
  DNAT for 51820 or 18766, and no peer had ever completed a handshake). It was recreated alone, and dot-files `ffe1c22` records the trap.
  So until then, no nightly after the reboot had actually reached YouTube.
- **46076397579 (v0.1.970): live phase ran.** The tunnel heartbeat was steady and GitHub stayed reachable for the whole run, which
  supports the tunnel fixes below. 14 of 15 suites passed, including `AnHourLongItemDoesNotRebufferTest`. `SeveralSharedLinksAllLandTest`
  failed: the third shared video played over HLS and was held by ADR 186. The test now pauses it, and the hold line says why (`2b2635a9`).
- **46083657361 (v0.1.973): fully green with the live phase running.**

A green nightly whose live phase skipped looks exactly like a healthy one. Read the `[live-test]` lines before trusting it.

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

## 27 Nov at v0.1.970: the one live failure

Run 46076397579 failed only `SeveralSharedLinksAllLandTest` ("not everything shared was fetched…", 304 s). Cause: the last shared link played as a
video over an HLS playlist, which the tee cannot cover, so the downloader held it while it streamed ([ADR 186](../adr/0186-one-fetch-per-item-a-playing-stream-is-its-own-download.md),
amended 27 Nov) and nobody paused it. The test now pauses playback once all three are queued; the hold line says why an item is not teeable.
From 28 Nov a YouTube video over HLS is fetched beside its stream (ADR 186 amended), so that case no longer waits; the test keeps its pause until a
nightly shows which route the last share takes (a SABR one is still held).

## 28 Nov at v0.1.980: `SilenceIsReallyCutTest` and the network it did not need

Run 46106878465 (`c845d23b`) failed `a video shows the frames of its sound and skips the frames of its pauses` with "no frame was ever drawn".
It had passed in the 12 runs before it that name their failures; the commit before it changed only the header-drag handler, which has nothing to do
with playback. The logcat says what happened: `setUp()` called `DeviceRadios.goUnmetered()`, which waited its 30 s, printed
`DeviceRadios: no unmetered network within 30000ms; timings may be disturbed` to a stdout nobody reads, and carried on. The Wi-Fi validated nine
seconds after the test (`net 105 : +VALIDATED`). With no unmetered network the playback mode AUTO means "listen", so the app routed the video as
`listen=true` (sound only) and drew no frame. The same family as 25 Nov (the radios `goOnline` gave up on): a test state that depended on the
emulator's network settling, with a guard that printed and carried on.

Fixed in two parts, one test and one support seam:

- **The test pins `PlaybackMode.VIDEO`** in `setUp` and restores it in `tearDown`, so it is about frames whatever the network is. Seen red first on
  `totum-api35` with its Wi-Fi metered (the route line read `listen=true`), at 1080x2400 and 320x640@160; with the pin the route reads
  `listen=false` and the picture assertions pass.
- **`DeviceRadios.goUnmetered()` is now `RadioRecovery`** (the retry `goOnline()` already had; both build it through one private `recovery(...)`).
  It returns whether an unmetered, validated network exists after three attempts, and no longer prints. Its one caller, this test, needed nothing
  from the network once the mode was pinned (every file it plays is local), so the call is gone, which also removes the 30 s `setUp` wait that hid this
  failure. Nothing calls `goUnmetered()` now; a future test that does need an unmetered network should `assumeTrue` its result (skip with a message) or
  `fail` it, according to what it asserts. A skip there would be visible in the report, unlike the old line.

The wall-clock assertions of this class (`took Nms to play 8000ms`, allowed 10000) are noisy on this local emulator: in 7 default-size runs after the pin
the video test's frames always drew (105 to 113 of 119 to 128 drawn frames from the sound), 3 runs were fully green, and 4 had one wall-clock miss (the
video's own `video with a picture` took 15.3 s and 15.6 s on the two runs right after an install, a podcast missed by 14 ms, the boosted quiet one by 1.3 s).
At 320x640@160 all 14 tests of the touched classes passed. The wall-clock assertions are untouched; whether the video one holds on the CI emulator with the picture
really decoded is not yet seen there (it never reached that assertion in the failing run).

## Still open

- `AnHourLongItemDoesNotRebufferTest` has not measured anything since 6 Nov, because it died at `playNow`. Its first real result
  is the nightly after this change: see [an-hour-long-item-stalls-early-in-ci](an-hour-long-item-stalls-early-in-ci.md).
- A nightly that tests the same commit for five days says nothing new. It is not changed here.
