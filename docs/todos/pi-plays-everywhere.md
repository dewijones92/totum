---
title: 📡 The Pi plays to any TV, browser or speaker; the phone is a remote
kind: todo
status: built 2026-11-07, Batch A (tests written 2026-11-07; device checks owed)
batch: A (2026-11), phase 7
priority: high
updated: 2026-11-07
---

# 📡 The Pi plays to any TV, browser or speaker; the phone is a remote

Dewi 2026-11-06: *"flexible casting … cast to any speaker, any browser, and any TV … not dependent on my phone, or on
the phone being connected all the time"*. Confirmed: the Pi does the work, the phone only controls; targets are the
LG TV, the Chromecast Ultra, PC browsers and the Pi's Bluetooth speakers (not the Fire Stick); in Batch A.

Today the phone is the brain of every cast (ADR 68) and serves the PC screen (ADR 81); only the speaker queues
(ADR 116) already play from the Pi. Already shared with the Pi: YouTube resolving signed in (`:core:video`), search,
torrents and home copies, queue operations (`:core:data`), mpv playback. Phone-only today: Cast control (the Pi's test
scripts drove the LG with pychromecast, so the protocol is fine there), the stream builder in `core/playback/cast`
(about two thirds of its 41 files are plain Kotlin and can move to a JVM module; the rest uses MediaCodec), and the
wrapping of the sound processing in Media3 audio processors. The processing itself (`SilenceCutter`, `SpeechModel`, `SpeechTrack`, `LoudnessBoost`, `VolumeBoost`, `LookaheadLimiter`) has no Android imports and already runs on the JVM in the audio-quality tests, fed by ffmpeg.

Plan: (1) a spike on the Pi relaying 4K VP9 to the LG while cutting silence; (2) generalise the speaker queues into
one "📡 Play on…" picker of targets; (3) Cast TVs and browsers as targets fed by the Pi; (4) any phone or the PC page
controls them. Sound on Pi-fed targets, decided by Dewi 2026-11-06 (option B): exactly the phone's processing. Move the plain-Kotlin processing into a JVM module shared by the phone and the Pi (no second copy); on the Pi, ffmpeg decodes to PCM and re-encodes after it. The spike measures the Pi's CPU for the speech model alongside a 4K relay. Best quality only and Totum's own step-down (cast-best-quality-only) apply here too.

Whole-batch review: speaker queues and Jellyfin fold in here. Scout facts: 26 of 41 cast files are plain Kotlin (manifest, relay, rungs, probes, `ScreenLink`, `CastHttpServer`) and move to a shared JVM module in phase 1, before the cast fixes; `CastStreams` is a split; the Pi reads audio with ffmpeg (no pure demuxer); the silero weights keep one copy for both loaders; `screen.html` is static and `ScreenLink` is pure; Cast control on the Pi is delegated to pychromecast in the Pi's Python env (as playback is delegated to mpv), proposed. Dewi 2026-11-06: speakers move onto the same processed path (skip silence, boost), so the Pi has one sound engine; mpv stays the speaker's player, fed the processed stream (ADR 116 updated when built).

## The phone as a remote: one queue screen with a target switch (Dewi, 2026-11-06)

Extends ADR 116's speaker decisions ("a thin layer … just like a different queue … its own colour") to every target:
- **One Queue screen**, a row of chips on top: 📱 Phone · 📺 LG TV · 🔈 Soundbar · ➕. A chip shows while that target
  has a queue; ➕ starts one on another target. The number of queues is the number of targets in use.
- **One queue component**: the phone's queue and the Pi's queues use the same list, drag, groups and menus; only the
  data source differs (no second queue UI).
- **A colour per target**: phone tangerine as today; every other device gets its own colour automatically, editable
  in that device's menu; used on its chip, queue header, mini-player pill and notification.
- **Play / Queue / Shuffle all go to the target being looked at**; a long press picks another.
- **Mini player**: the phone's item plus one pill per other target ("📺 Bluey · 🔈 Vivaldi"); a tap opens that queue.
  **Notifications**: one per active target, each with its own controls.
- **"Send to TV" from what the phone plays** moves that item plus the rest of its group, and the phone pauses.

## Done: the shared JVM modules (2026-11-06, [ADR 119](../adr/0119-cast-and-sound-code-shared-with-the-pi.md))

The mechanical move of phase 1 is built, behaviour unchanged:

- **`:core:cast`** (pure JVM, `explicitApi()`): the 26 plain-Kotlin cast files the scout listed (all verified free of
  Android imports), plus `CastProblem`/`CastStage` and the TV port (`CastTv`, `CastTvPort`, split out of `CastStatus`).
  Their tests moved with them (all of `core/playback/src/test/…/cast` except `EmbeddedSubtitlesTest` and
  `ScreenCaptionsTest`, which test files that stay, and `PcmDownmixTest`, which moved from the package root).
  `CastStreams` was split on 2026-11-07 (ADR 156).
- **`:core:sound`** (pure JVM, `explicitApi()`): `SilenceCutter`, `SpeechModel`/`SpeechWeights`, `SpeechTrack`,
  `LoudnessBoost`, `VolumeBoost`, `LookaheadLimiter`, with their tests and the whole audio-quality suite
  (`:core:sound:test -Ptotum.audioQualityTests`; `audio-quality.yml` and the preflight check follow it).
- **One copy of the weights**: `silero_vad.bin` is in `:core:sound`'s Java resources and is loaded by
  `SpeechWeights.bundled()` everywhere (`BundledSpeechModel`, the unit tests, the audio-quality suite, the instrumented
  tail-scan test). The release APK was built with R8 and still holds it at its root.
- **Package names unchanged**; `:core:playback` depends on both with `api`.
- **Open for the testing stretch**: `:core:cast` is Kover-exempt at 65.2% of lines covered (bar 75%); the gaps are
  `CastHttpServer`, `WebmLayout`, `Fmp4Aac`, `DashManifest`, `LocalRanges`, `Upstream`, `TvVideos`, `CastTimeline`.

## Spike, 2026-11-06 (phase 1): the Pi can do it

Measured on the Pi 4 inside `totum-fetch` (Java 21, ffmpeg 8.0.1), load 0.4 before: ffmpeg decodes 60 s of Opus to PCM
in 0.56 s (~107x real time) and encodes 60 s of AAC in 2.1 s (~28x); the shared `:core:sound` speech model (the jar
built from ADR 119's module, the same bytes the phone runs) takes 60 s of audio in 1.9 s (~31x real time, one core,
two runs; identical output to the laptop's run). The whole chain is ~13x real time on one of four cores, so several
targets at 2x speed fit; 4K video is relayed, not processed. Verdict: build phase 7 as designed.

Cast arming (2026-11-06): the next item reaches the TV only in its last 75 s (cast-progress-and-end), so the TV remote's own "next" does nothing before then; when the Pi drives the TV, map the remote's next to Totum's queue.

## Other phones in the house (Dewi, 2026-11-06)

Any phone signed in to the home server (Dewi's wife's included) sees and controls the Pi's target queues exactly as
Dewi's phone does: see what plays, add, reorder, remove, play/pause. Each phone's own queue stays private. Built in:
live updates across phones (a change shows on the others within a second or two); edits sent as small operations
(add X after Y, remove Z), never a whole-queue replace, so two phones editing at once don't clobber each other; each
item shows which phone added it. YouTube: the Pi plays with Dewi's signed-in account, so only items queued from
Dewi's phone report watch progress to YouTube; items queued from another phone don't. Her sign-in goes through the
same Google-gated home-server sign-in; check her account is admitted by that gate when building.

## Speakers: one owner, different things at once (Dewi, 2026-11-06)

- Totum's queues become the only thing that drives the Bluetooth speakers. Home Assistant's brown-noise and jazz
  switches (`hermes-audio-ctl` on the Pi, `127.0.0.1:8099`, in dot-files) were a second player on the same speakers,
  never considered in ADR 116; they will ask Totum to play on a speaker instead, so they show in its queue view and
  cannot fight it for the link. That changes dot-files and the HA config (commit only these files there).
  Recorded on the Pi as dot-files ADR 0009 (2026-11-07): Totum owns the speakers, `hermes-audio-ctl` gets no new
  sources, and anyone touching a speaker by hand checks `GET /targets` first.
- Each speaker plays its own queue at the same time as the other (different things in different rooms); no group
  target and no syncing. Prove the Pi's adapter holds two A2DP streams at once without stutter (Pi 4's onboard radio
  shares Bluetooth and Wi-Fi).
- The leftover `totum-speaker-test` container from 2026-11-01 was removed on 2026-11-06.

## Built 2026-11-07 (code first; tests written 2026-11-07)

Feature doc: [pi-plays-everywhere](../features/pi-plays-everywhere.md). Decisions: ADRs
[152](../adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) and
[153](../adr/0153-the-pi-runs-the-phones-sound-processing.md). Built overnight with no Pi, TV, speaker or Chromecast in
reach, so nothing below has run against a real device.

- **Seams:** the target model (`data/targets`), `PcmChain` in `:core:sound`, the Pi's `TargetDeck`/`TargetDecks`/
  `TargetRoutes`, queue-per-target on `/targets`, `TargetsClient`, `TargetsRepository`, the `QueueDestination` hook in
  `PlaybackQueue`.
- **Pi players:** speakers (mpv, now through `PcmPump` into mpv's stdin when the sound is processed), Chromecast TVs
  (`pychromecast` helper, `SoundStreams` serving processed AAC and home copies).
- **Phone:** Queue-tab chips, a coloured remote queue on the same row, 📡 Send to…, redirected Play/Add/Shuffle, the
  progress-strip operation.
- **YouTube:** reported from the Pi only for items from the owner's phone.

### Left (nothing half-wired; each is absent, not stubbed)

PC browsers as targets (`TargetKind.BROWSER` exists, nothing registers one); Home Assistant's brown noise and jazz through
Totum (a `dot-files` and HA change: make `hermes-audio-ctl` call `POST /targets/queue?id=<speaker>`); the mini-player pill
and per-target notification; "Play on…" as a long press on every play button (the selected chip decides today); processing
(the DASH path of ADR 68 on the Pi is built, ADR 156); playback speed, subtitles,
TV volume strip and Cast arming on a target; the Pi measuring two A2DP streams at once.

### Pi-side assumptions (not verified)

- `totum-fetch` can publish port 8771 on the LAN and the TVs reach the Pi's LAN address on it; `TOTUM_CAST_BASE` and
  `TOTUM_CAST_TARGETS` are set in `/var/lib/totum-fetch/fetch.env`.
- `pychromecast` 14's API (`get_chromecasts(known_hosts=…)`, `media_controller.play_media(…, current_time=…)`,
  `register_status_listener`) is what `chromecast_helper.py` was written against; it was only compile-checked.
- The container's bridge network lets it open the Chromecast's port 8009 on the LAN.
- A Chromecast plays an unseekable ADTS AAC stream sent without a length (processed audio).
- Pi's PulseAudio and Bluetooth stay as ADR 116 set them up; mpv accepts raw PCM on stdin with `--demuxer=rawaudio`.

### Review fixes, 2026-11-07

Findings of the end-of-batch review and two sweeps, fixed: the target chip no longer outlives the Queue tab, and Kid TV,
kid-locked shortcuts and the Shorts reel always play on the phone; a redirect reports what the Pi queued and falls back
to the phone with a notice; send-to is an insert; the YouTube owner is the requester the Pi recorded at the hand-over
(a second phone's hand-over or sign-out is refused); skip silence follows the per-kind rule on the Pi; a Chromecast is
retried after a failure and stale idle statuses are ignored; connecting happens outside the deck lock; TV sound switches
are greyed over a picture; play next on a remote row; the target poll is lifecycle-gated. See ADRs 152, 153, 154, 108.
Still owed from them: carrying the phone's silence mode and speech gate to a target; hiding the Jellyfin tile when
Jellyfin is off; Add to queue and Peek on a remote row (recorded as exceptions).

### Tests

Exist (JVM): `TargetDeckTest`, `TargetStreamsTest`, `YouTubeProgressTest`, `PcmPumpTest`, `CastWireTest`, `CastControlTest` (a
fake helper process), `TargetRulesTest`, `TargetsRepositoryTest`, `PlayLocallyTest`, and, new:

- `TargetRoutesTest` (the list, a queue that starts, a refusal as a 422 with its words, a control, no id, an unknown target,
  an unreadable body, only GET lists and only POST acts) and `TargetDecksTest` (every source's targets together, a deck kept
  between asks, a failing source not hiding the others, the sources looked at once a minute, a player's end moving its deck
  on); `DeckStoreTest`.
- `RedirectEntryPointsTest` (all six `PlaybackQueue` entry points hand a person's own request to the target with its mode,
  group and start item; `byYou = false` is never redirected; an empty request is not sent; a target that does not take it
  leaves the phone to play it; no target means everything plays here); `SendToTargetTest` (what plays and the rest of its
  group go from the second it was at and leave the phone, a refusal or a target that did not start leaves the phone playing
  with its queue whole). Seen failing: `setPlaying(false)` removed, and the `byYou` guard removed.
- `MpvPlayerTest` (the real command line against a script that records its arguments: unprocessed from the address with
  `--start`, an address that begins with a dash after `--`, boosted items decoded by ffmpeg from where they were and fed as raw
  48 kHz stereo, a clean exit is not a failure, a failing one is, stopping and replacing report nothing); `SoundStreamsTest`
  (no base means nothing offered, unguessable addresses, processed AAC and ranged files served, withdraw, ten-minute expiry);
  `ChromecastPlayerTest` (processing only for sound with a server, a picture never processed, a file offered over HTTP,
  positions on a processed stream add where it started, content types, configured casts become typed targets);
  `ProcessedSoundEndToEndTest` (a real ffmpeg on a generated clip: 48 kHz stereo bytes, starting a second in, ADTS AAC that
  ffprobe reads as the right length; ffmpeg is required and CI installs it). Seen failing: mpv's `--` removed, and the
  Chromecast picture guard removed.
- `:core:cast` now reaches 87.5% of its lines and is held by `koverVerify` (it was exempt at 63.8%).

Instrumented, compiled and not run: `TargetChipsTest` (a target with a queue gets a chip, idle ones wait under ➕, a tap moves
the selection, no targets means no chips).

Still owed, with the reason:

- The YouTube relay on the real LG and Chromecast: a video sent from the phone plays past 4 s and to its end, seeks,
  and its Pi log shows `stream relayed at /y/…` with ranges; a YouTube sound to a speaker (mpv) and a boosted one
  through the relay.
- Real-device day pass (volume rule: TV and speakers at 3% or lower, short silent or very quiet clips, volume restored):
  send a quiet clip from the phone to the LG, then the Chromecast, then the soundbar; pause, resume, next, jump, remove,
  volume; skip silences and boost on a speaker and on a TV; two speakers at the same time (stutter); a YouTube video
  queued from the owner's phone appears in YouTube history, one from another phone does not; the colour of a chip.
- Compose: `RemoteQueue` rows and `SendToButton` (device).
- `PyCastControl` against a real Chromecast and the real `chromecast_helper.py` (the Pi).
