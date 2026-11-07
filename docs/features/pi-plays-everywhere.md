---
title: 📡 The Pi plays to any TV, speaker or browser; the phone is a remote
kind: feature
area: casting/pi
status: built 2026-11-07, Batch A (tests owed; nothing run on a real TV, speaker or Chromecast)
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
  that target, not on the phone. 📱 This phone puts everything back. Every send shows in the progress strip
  ("📡 Sending to …", then "✅ Sent 12 items to …" or the reason it failed).
- **📡 Send to… in the Queue header** moves what plays: the playing item and the rest of its group, at the second it was
  at. The phone pauses and drops them once the target says it is playing; if the target does not start, the phone keeps
  its queue and the strip says why.
- **Speakers are targets.** Any paired Bluetooth device with an Audio Sink is listed, linked only while it plays
  (ADR 116, unchanged), each with its own queue at the same time as the others.
- **TVs and Chromecasts** listed in `TOTUM_CAST_TARGETS` (name=address, `;`-separated) are driven through `pychromecast`.
  A YouTube video goes to the TV as the same address the phone would play, so the TV fetches it itself from the same home
  IP; a home copy is served by the Pi (`SoundStreams`, ranged, a random token in the path). Audio the user has
  processed (skip silences, boost) is decoded, processed and AAC-encoded on the Pi and served to the TV.
- **YouTube progress** is reported from the Pi for items queued from the owner's phone only, on pause, skip, an item
  ending and every 30 s; items from another phone are not.

## Files

| Piece | Where |
|---|---|
| Model, colours, send plan, reporting rule | `core/data/…/data/targets/` (`Targets.kt`, `TargetRules.kt`), `data/home/TargetsClient.kt` |
| PCM chain (cut, then boost) | `core/sound/…/PcmChain.kt` |
| Pi decks, routes, reporting | `cli/…/serve/targets/` (`TargetDeck`, `TargetDecks`, `TargetRoutes`, `DeckStore`, `YouTubeProgress`) |
| Pi sound pipelines | `cli/…/serve/sound/ProcessedSound.kt` |
| Speakers | `cli/…/serve/speakers/` (`Bluez.kt`, `MpvPlayer.kt` — now a `TargetPlayer`, `SpeakerSource`) |
| TVs | `cli/…/serve/cast/` (`ChromecastPlayer`, `CastControl`, `CastWire`, `SoundStreams`), `server/totum-cli/chromecast_helper.py` |
| Phone | `app/…/targets/` (`TargetsRepository`, `SendToTarget`, `TargetColourStore`, `RequesterIdentity`), `ui/queue/` (`QueueWithTargets`, `TargetChips`, `RemoteQueue`, `SendToButton`), `queue/QueueDestination.kt` |

## Not built (see the todo)

PC browsers as targets; Home Assistant's brown noise and jazz through Totum (a dot-files change); the mini-player pill
and per-target notification; "Play on…" as a long press on every play button; sound processing on a picture (the DASH
path of ADR 68 on the Pi); playback speed and subtitles on a target; TV volume in the strip.

## Tests

Unit: `PcmChainTest`, `TargetRulesTest`, `TargetEntriesTest` (core), `TargetDeckTest`, `YouTubeProgressTest`,
`PcmPumpTest`, `CastWireTest` (cli). Owed: see the todo.
