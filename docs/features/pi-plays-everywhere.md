---
title: 📡 The Pi plays to any TV, speaker or browser; the phone is a remote
kind: feature
area: casting/pi
status: built 2026-11-07, Batch A (tests written 2026-11-07; volume rule and the YouTube relay fixed after the first run on the LG; the relay not yet seen on a real TV)
updated: 2026-11-13
---

# 📡 The Pi plays to any TV, speaker or browser

The Pi holds one queue per target and plays it; the phone shows and controls those queues. Decisions:
[ADR 152](../adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (targets, queues, send-to,
colours, who reports to YouTube) and [ADR 153](../adr/0153-the-pi-runs-the-phones-sound-processing.md) (the phone's own
sound processing on the Pi). Brief: [pi-plays-everywhere.md](../todos/pi-plays-everywhere.md).

## What it does

- **Queue tab, chips on top.** 📱 This phone, then a chip for each target that has a queue (coloured, with its kind's
  emoji: 🔈 speaker, 📺 TV or Chromecast, 🖥️ browser), and ➕ Play on… for targets with nothing queued. The chips show
  once the home server lists at least one target.
- **A target's queue is the same list.** The same `MediaItemRow`, group headings, jump (tap), remove (menu) as the
  phone's own queue; above it what plays, the position, ⏮ ⏯ ⏭ ⏹️, a volume slider, ✂️ Skip silences and 🔊 Boost (the
  phone's own processing, run on the Pi), and 🎨 Colour. Each row says "➕ Added by …" when the phone that added it is known.
- **Where taps go.** While a remote chip is selected, Play, Add to queue, Play next, Shuffle all and the rest queue on
  that target, not on the phone. 📱 This phone puts everything back, and so does leaving the Queue tab. Kid TV, kid-locked
  shortcuts and the Shorts reel always play on the phone. Every send shows in the progress strip
  ("📡 Sending to …", then "✅ Sent 12 items to …"); if the Pi refuses, cannot be reached or cannot start, the phone
  plays the items itself and the strip says why.
- **📡 Send to… in the Queue header** moves what plays: the playing item and the rest of its group, at the second it was
  at, inserted after what plays on the target (the target's other items stay). The phone pauses and drops them once the
  target says it is playing; if the target does not start, the phone keeps its queue and the strip says why.
- **Speakers are targets.** Any paired Bluetooth device with an Audio Sink is listed, linked only while it plays
  (ADR 116, unchanged), each with its own queue at the same time as the others.
- **TVs and Chromecasts** listed in `TOTUM_CAST_TARGETS` (name=address, optionally `@mac`, `;`-separated) are driven through `pychromecast`.
  **A TV loads what the phone would load** ([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)):
  `…/c/<token>/manifest.mpd` (`application/dash+xml`, stream type buffered) served by the same `CastStreamServer` as the
  phone's. A YouTube video is the phone's inputs (the chosen video stream, its own audio, the ladder), so the TV gets the
  best picture it can show, relayed untouched, and the sound as processed AAC segments (the Pi's ffmpeg and `PcmChain`);
  a home copy, a Jellyfin film, a podcast and a file on the Pi are cast as the file or address they are; a TV that cannot
  show the video gets sound only and the target says why. A live stream gets `live.mpd` with its segments relayed by the
  Pi. Seeking works on a processed item, and with silences cut (sound only) the position is the source's. A stream the
  core declines (a single muxed YouTube stream, a third party's manifest) is given as it is, through `/y/<token>` (2 MB
  ranges, because YouTube stops a plain fetch after about a megabyte); the same relay feeds mpv and ffmpeg over loopback.
- **YouTube progress** is reported from the Pi for items queued from the phone that signed the Pi in only (the Pi records
  which phone handed the sign-in over), on pause, skip, an item ending and every 30 s; items from another phone are not.
  A second phone's sign-in hand-over is refused until the first phone signs the Pi out.
- **Volume is the device's own.** A play never changes a TV's or a speaker's volume; only the phone's volume slider does.
  The slider shows the level the device reports (a Chromecast says it on connect and when it changes), or "🔈 Volume not
  known" when it has not said. A volume saved by an older version is ignored.
- **A speaker's volume is the speaker's own** ([ADR 165](../adr/0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md)). The slider writes the Bluetooth transport's
  absolute `Volume` (`busctl`, 0 to 127) and shows what the speaker reports, including a change made on its own buttons;
  mpv stays at 100%. With no transport or no `Volume` property it falls back to mpv's volume and the line says "🔉 …
  Totum's, the speaker's own is not available". Reading never writes.
- **A speaker can be kept awake.** `TOTUM_SPEAKERS_KEEP_AWAKE=Soundmic` (or `Soundmic@07:00-22:30`; names or ids) keeps
  that speaker linked when a queue ends, reconnects it if it drops (2, 4, 8, 10 minutes apart) and sends two seconds of
  inaudible sound to its sink about once a minute while nothing plays (`TOTUM_SPEAKERS_KEEP_AWAKE_SIGNAL=silence|noise`).
  The remote panel says "🟢 Kept awake", "⏳ … reconnecting…" or "💤 … outside its hours". Unnamed speakers, the soundbar
  included, are untouched. Built and unit-tested 2026-11-13; **not yet seen on the real Soundmic**.
- **TV sound switches** are greyed, with a note, while the TV is showing a picture.
- **A TV that is off is listed and wakes on a play.** `TOTUM_CAST_TARGETS` accepts `name=address@aa:bb:cc:dd:ee:ff`; such a
  TV shows 💤 on its chip and "💤 Off · tap to wake" in its header while it is off. Playing to it (or tapping that) sends
  Wake-on-LAN, shows "⏰ Waking LG TV…" on the progress strip, waits up to 25 s for it to answer, then plays; if it never
  does the strip says "❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings". Untested from the container (see
  ADR 152).
- **Live on a speaker** plays YouTube's HLS manifest for the video (via yt-dlp on the Pi), from the live edge; TVs get
  the DASH manifest as before ([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)).
- **A slow start is watched, not guessed at** ([ADR 158](../adr/0158-targets-end-of-batch-fixes.md)). A queue request is
  accepted at once; a speaker that takes 15 s to link, a TV that is waking or a slow lookup show as "⏳ Starting on …" on
  the strip (the state says `starting`) and the phone watches until the Pi has played it or said why not. Only then, if it
  did not, does the phone play the items itself, taking them off the Pi by id. A target that cannot be reached is not asked
  for a stream first, and its problem is shown in the phone's own words ("📶 Couldn't reach … it isn't connected (off or out
  of range)", "📶 Switch … on, then try again", "🔑 Sign in to the home server again", "⏳ YouTube is refusing the Pi for
  now; try again around 14:30").
- **A TV switched off mid-item stops the deck and keeps its place.** The deck shows it asleep with "📺 LG TV was switched
  off"; Play wakes it and resumes at the saved second (a live item at the edge). STOP then Play, and a Pi restart then Play,
  also resume. A playing item whose position has not moved for 20 s is a "stopped moving" problem.
- **The remote panel seeks.** A slider (sent on release) with 10 s back and 30 s forward; tapping the playing row does not
  restart it.
- **A reason stays visible.** When an item fails and the queue moves on, the target's problem stays on screen until the
  next item ends or you act ([ADR 155](../adr/0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)).
- **STOP clears the TV.** If Totum opened the receiver app (the TV was idle or on its backdrop), STOP, or a queue that
  runs out, quits it, so the TV is not left on a blank "Default Media Receiver" screen; if another app was running first
  (YouTube, say) the TV is left alone.
- **A live stream has no length.** The strip shows "🔴 LIVE" instead of the bar and the clock (the TV's own "length" of
  a live stream is YouTube's 12-hour window, and `GET /targets` now sends none, with `live: true`).

## Files

| Piece | Where |
|---|---|
| Model, colours, send plan, reporting rule | `core/data/…/data/targets/` (`Targets.kt`, `TargetRules.kt`), `data/home/TargetsClient.kt` |
| PCM chain (cut, then boost) | `core/sound/…/PcmChain.kt` |
| Pi decks, routes, reporting | `cli/…/serve/targets/` (`TargetDeck`, `TargetDecks`, `TargetRoutes`, `DeckStore`, `YouTubeProgress`) |
| Pi sound pipelines | `cli/…/serve/sound/ProcessedSound.kt` |
| Speakers | `cli/…/serve/speakers/` (`Bluez.kt`, `MpvPlayer.kt` — now a `TargetPlayer`, `SpeakerSource`) |
| TVs | `cli/…/serve/cast/` (`ChromecastPlayer`, `PiStreams` (owns the `CastStreamServer`), `PiCastTvs`, `FfmpegAudioEngine`, `CastControl`, `CastWire`, `StreamRelay`), `server/totum-cli/chromecast_helper.py`; the brain in `core/cast` (`CastStreamServer`, `ProcessedAudio`, `AudioEngine`, `LiveRelay`, `DashManifest`, `CastRungs`, `ChunkedUpstream`, `LocalRanges`), `HomeDownloads.castOf` for a YouTube ladder |
| Phone | `app/…/targets/` (`TargetsRepository`, `TargetWords`, `SendToTarget`, `TargetColourStore`, `RequesterIdentity`), `ui/queue/` (`QueueWithTargets`, `TargetChips`, `RemoteQueue`, `SendToButton`), `queue/QueueDestination.kt` |

- **Repeat, files and problems (2026-11-07, [ADR 155](../adr/0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)).** A
  target repeats off / this one / the queue (set by a queue request or a `REPEAT` control, saved, shown in `GET /targets`);
  an item can be a sound file on the Pi (`file:///sounds/<name>`, nothing outside that folder); a passed-over item or a
  failed player shows as the target's `problem`; `127.0.0.1:8772` on the Pi serves only `/targets*` and `/health`, for Home Assistant ([ADR 158](../adr/0158-targets-end-of-batch-fixes.md)).

- **End-of-batch fixes (2026-11-13, [ADR 158](../adr/0158-targets-end-of-batch-fixes.md)).** Accept-then-start, reach before
  resolve, resume from the saved place, a lost connection, a stall, edits and removals by item id, a structured problem
  (`TargetState.trouble`), the YouTube back-off wording, the seek slider, audio extensions only in `/sounds`, one kit per
  target, the cut-end race, the hand-back remembered across a restart (`handback-<id>.json`),
  `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND` for speakers that must be switched on by hand.

## Not built (see the todo)

PC browsers as targets; Home Assistant's brown noise and jazz through Totum (the Totum side is built; the dot-files adapter is in `tools/handoff/hermes-audio/`, not deployed); the mini-player pill
and per-target notification; "Play on…" as a long press on every play button; skip silences on a picture (the phone does
not either); playback speed on a target; the phone's silence mode and speech gate on a target; TV volume in the strip.
Not seen on a real TV or speaker yet: the manifest, the relayed picture, the Pi's segments, live on a TV and on mpv.

## Tests

ADR 158 (2026-11-13): `TargetDeckStartTest`, `TargetRoutesSlowStartTest`, `TargetDecksTest`, `SoundFilesTest`,
`TargetStreamsTest`, `BluezTest`, `CastBrainTest`, `CastControlTest`, `CastWireTest`, `WakeThenPlayTest` (cli),
`TargetsRepositoryTest` (app), `chromecast_helper_test.py`, `test_audio_ctl.py`; `TargetWordsTest` (instrumented, not run).

Unit (wake, hand back, live, ADR 152 amendments of 2026-11-07): `WakeOnLanTest`, `TvWakeTest`, `WakeThenPlayTest`, `YtDlpLiveHlsTest`, `LiveForSpeakersTest`, `CastWireTest`, `CastControlTest`, `ChromecastPlayerTest`, `TargetDeckTest`, `MpvPlayerTest` (cli), `TargetRulesTest` (core), `TargetsRepositoryTest`, `TargetChipLabelTest` (app), `server/totum-cli/chromecast_helper_test.py`.

Unit: `PcmChainTest`, `TargetRulesTest`, `TargetEntriesTest` (core), `TargetDeckTest`, `YouTubeProgressTest`,
`PcmPumpTest`, `CastWireTest`, `CastControlTest`, `YouTubeOwnershipTest` (cli); `TargetsRepositoryTest`, `PlayLocallyTest`,
`SignInHandoverTest`, `RequesterIdentityTest` (app); and from the 2026-11-07 testing stretch `TargetRoutesTest`,
`TargetDecksTest`, `DeckStoreTest`, `MpvPlayerTest`, `ChromecastPlayerTest`,
`ProcessedSoundEndToEndTest` (cli), `RedirectEntryPointsTest`, `SendToTargetTest` (app); and from the two fixes after the
first run on the LG `StreamRelayTest` (cli), `ChunkedUpstreamTest`, `LocalRangesTest` (core/cast) for the relay, and the
volume tests in `TargetDeckTest`, `ChromecastPlayerTest`, `MpvPlayerTest`, `CastControlTest`, `CastWireTest`; and from ADR 155 `TargetRepeatTest` (core), `SoundFilesTest` and the repeat, problem and requester cases of `TargetDeckTest`, `TargetRoutesTest`, `TargetStreamsTest`, `MpvPlayerTest` (cli). Owed: see the todo.

From ADR 156: `CastBrainTest` (a normal video loads exactly the manifest the phone builds, picture relayed by range, sound-only, live on a TV and on a speaker, cut position and end, the wire), `FfmpegAudioEngineTest` (segments, seeks, cuts, stale stop, length, silent frame, ADTS), `PiCastTvsTest`, and the `streamFor` cases of `TargetStreamsTest` (cli); `EmbeddedSubtitlesTest` moved to `:core:cast`. The phone's `CastStreams` is covered by the same core tests and by its instrumented tests, not run in this change.
