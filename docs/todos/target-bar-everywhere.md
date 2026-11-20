---
title: 🎛️ Control the selected Pi device from every tab: a now-playing bar in its colour, a lighter remote page
kind: todo
status: built 2026-11-20 (unit and instrumented tests written; screenshots at 1080x2400 and 320x640@160; not driven on a real TV or soundbar; lock-screen controls not built)
batch: C, task D
priority: high
updated: 2026-11-20
---

# 🎛️ Control the selected device from anywhere

Dewi, 2026-11-20: *"do at least the basic play controls from my app and whatever receiver … play pause, rewind etc"*, and
*"yes, with different colors etc etc, so I can really distinguish"*. Decision and design:
[ADR 180](../adr/0180-the-selected-device-owns-the-dock-bar-and-the-remote-page-is-a-card-over-its-list.md). Feature doc:
[pi-plays-everywhere](../features/pi-plays-everywhere.md).

## Reuse (searched before building)

- **The mini player's layout:** `MiniPlayerBar` was split into `NowPlayingBarFrame` and `ArtworkWithBadge`; the device's bar is that frame
  with other slots. No second player.
- **The device's actions:** `TargetAction` through `TargetsRepository.command` / `act`, the same calls the remote page made.
- **Seek steps:** `SeekSteps` (made public) replaces the page's own copy of 10 s and 30 s.
- **Controls:** the player's `ControlTile` family (`RepeatTile`, `SleepTimerControl`, `SkipSilenceTile`, `ToggleTile`) replaces the page's
  filter chips and bespoke rows.
- **Fake targets for screenshots and tests:** `FakeAppContainer(targetsApi = …)` with a `TargetsApi` fake, and `TestShots` (`-e shots 1`), as
  `TheQueueTabSpendsItsHeightOnTheListTest` and `RemoteQueueTest` already did.

## Done

- [x] A bar for the selected device on every tab, replacing the phone's mini player while the device has something to show
  (`barFor`, `TargetNowPlayingBar`); title, art, play/pause, back 10, forward 30, skip, device name; tinted and bordered in its colour;
  a tap opens its remote page.
- [x] Remote page: a compact card as the first item of one `LazyColumn`; a filled play/pause, plain skips, an icon-only Stop; a seek bar
  in the device's colour with elapsed and total at its ends; a volume line (icon, percentage, short label, reason behind an info tap);
  Repeat, Sleep, Skip silences, Boost and Colour behind one "⚙️ Controls" row, all one tile component; a "📋 Up next on LG TV · 54" heading;
  a heading with the link state; heading text in the screen's own colour.
- [x] Chips: a connection dot, a scroll affordance, a glyph per kind (Chromecast is now 📽️), colour on a long-press, swatches 48 dp.
- [x] Pi: the A2DP transport at `dev_MAC/sepN/fdN`; discovery lines on change only; keep-awake backs off on "Host is down".

## Open

- [ ] **Lock-screen and notification controls for a device** (Dewi: later). The phone's media notification is the phone's own
  `MediaSession`; a device's would be a second notification (or a pill) fed by the same polled `TargetState`. The bar and the actions are
  built, so this is one more surface.
- [ ] Whether 📽️ is the right glyph for a Chromecast (one string, `TargetKind.CHROMECAST`).
- [ ] The device's progress and the bar move with the poll (2 s on the Queue tab, 3 s elsewhere), not smoothly; interpolating between reads
  while it plays would be a small addition if it looks jumpy.
- [ ] Check on the Pi that the corrected path finds the soundbar's transport: `busctl --system tree org.bluez --list | grep sep`, then move
  the slider and read the `speakers` log (`set Volume … on /org/bluez/hci0/dev_…/sep1/fd…`).
