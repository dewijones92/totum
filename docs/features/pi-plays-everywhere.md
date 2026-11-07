---
title: 📡 The Pi plays to any TV, speaker or browser; the phone is a remote
kind: feature
area: casting/pi
status: built 2026-11-07, Batch A (tests written 2026-11-07; volume rule and the YouTube relay fixed after the first run on the LG; the relay not yet seen on a real TV)
updated: 2026-11-07
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
- **TVs and Chromecasts** listed in `TOTUM_CAST_TARGETS` (name=address, `;`-separated) are driven through `pychromecast`.
  A YouTube video goes to the TV through the Pi: the Pi serves it at `/y/<token>.<ext>` and fetches it from YouTube in
  2 MB ranges (YouTube stops a plain fetch after about a megabyte, which is what ended the first send to the LG after 3
  seconds), whatever the TV asks for; the same relay feeds mpv and ffmpeg on the Pi over loopback. A home copy is served by
  the Pi (`SoundStreams`, ranged, a random token in the path). Audio the user has processed (skip silences, boost) is
  decoded, processed and AAC-encoded on the Pi and served to the TV.
- **YouTube progress** is reported from the Pi for items queued from the phone that signed the Pi in only (the Pi records
  which phone handed the sign-in over), on pause, skip, an item ending and every 30 s; items from another phone are not.
  A second phone's sign-in hand-over is refused until the first phone signs the Pi out.
- **Volume is the device's own.** A play never changes a TV's or a speaker's volume; only the phone's volume slider does.
  The slider shows the level the device reports (a Chromecast says it on connect and when it changes), or "🔈 Volume not
  known" when it has not said; a speaker's is the level last chosen from the phone, until the Pi restarts. A volume saved
  by an older version is ignored.
- **TV sound switches** are greyed, with a note, while the TV is showing a picture.

## Files

| Piece | Where |
|---|---|
| Model, colours, send plan, reporting rule | `core/data/…/data/targets/` (`Targets.kt`, `TargetRules.kt`), `data/home/TargetsClient.kt` |
| PCM chain (cut, then boost) | `core/sound/…/PcmChain.kt` |
| Pi decks, routes, reporting | `cli/…/serve/targets/` (`TargetDeck`, `TargetDecks`, `TargetRoutes`, `DeckStore`, `YouTubeProgress`) |
| Pi sound pipelines | `cli/…/serve/sound/ProcessedSound.kt` |
| Speakers | `cli/…/serve/speakers/` (`Bluez.kt`, `MpvPlayer.kt` — now a `TargetPlayer`, `SpeakerSource`) |
| TVs | `cli/…/serve/cast/` (`ChromecastPlayer`, `CastControl`, `CastWire`, `SoundStreams`, `StreamRelay`), `server/totum-cli/chromecast_helper.py`; the ranged reader `ChunkedUpstream`, `PatientUpstream` and `LocalRanges.replyFor` in `core/cast` |
| Phone | `app/…/targets/` (`TargetsRepository`, `SendToTarget`, `TargetColourStore`, `RequesterIdentity`), `ui/queue/` (`QueueWithTargets`, `TargetChips`, `RemoteQueue`, `SendToButton`), `queue/QueueDestination.kt` |

## Not built (see the todo)

PC browsers as targets; Home Assistant's brown noise and jazz through Totum (a dot-files change); the mini-player pill
and per-target notification; "Play on…" as a long press on every play button; sound processing on a picture (the DASH
path of ADR 68 on the Pi); playback speed and subtitles on a target; TV volume in the strip.

## Tests

Unit: `PcmChainTest`, `TargetRulesTest`, `TargetEntriesTest` (core), `TargetDeckTest`, `YouTubeProgressTest`,
`PcmPumpTest`, `CastWireTest`, `CastControlTest`, `YouTubeOwnershipTest` (cli); `TargetsRepositoryTest`, `PlayLocallyTest`,
`SignInHandoverTest`, `RequesterIdentityTest` (app); and from the 2026-11-07 testing stretch `TargetRoutesTest`,
`TargetDecksTest`, `DeckStoreTest`, `MpvPlayerTest`, `SoundStreamsTest`, `ChromecastPlayerTest`,
`ProcessedSoundEndToEndTest` (cli), `RedirectEntryPointsTest`, `SendToTargetTest` (app); and from the two fixes after the
first run on the LG `StreamRelayTest` (cli), `ChunkedUpstreamTest`, `LocalRangesTest` (core/cast) for the relay, and the
volume tests in `TargetDeckTest`, `ChromecastPlayerTest`, `MpvPlayerTest`, `CastControlTest`, `CastWireTest`. Owed: see the todo.
