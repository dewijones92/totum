---
title: ▶️ Play on… on every play and queue action, with home devices always listed and woken
kind: todo
status: built 2026-11-13 (unit tests green; instrumented tests written and compiled; nothing run on a real TV, soundbar or the Soundmic)
batch: B (2026-11), P4
priority: high
updated: 2026-11-13
---

> **Follow-ups done 2026-11-13 ([ADR 170](../adr/0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md)):** a device
> switched on by hand now says "🔌 Off · switch it on by hand" (`TargetState.switchedOnByHand`, Pi-side power from the Bluetooth
> sink, no wake sent on a pick); one rule for a device's colour on every surface (`TargetColours.of`), with the one real gap fixed
> (a device first met in a queue answer was unpainted until the next read); the Browser target is built and slots in as this page said it
> would. The open points below are otherwise unchanged.

# ▶️ Play on… on every play and queue action

Dewi, 2026-11-13: *"Device picker on every play/queue action, one-tap default to the current device; each device a
queue with its own colour, shown on playing items everywhere."* and *"when I'm home, I want my home devices to be listed,
even if they're off, and I want our solution to switch them on so it's all convenient."*

Decision and design: [ADR 169](../adr/0169-play-on-is-a-choice-that-remembers-the-last-device.md). Feature doc:
[pi-plays-everywhere](../features/pi-plays-everywhere.md).

## Reuse (searched before building)

- **Targets, queues, colours, chips:** ADR 152's model (`Target`, `TargetState`, `TargetColours`, the Queue chips) is
  unchanged and reused whole; the idle-target menu in the chips now draws through the same `PlayOnMenuItems` as the new row.
- **Where a play goes:** `PlaybackQueue.destination` and `TargetsRepository.take` already redirect every deliberate play;
  only the *choice* it reads changed, so no entry point was touched.
- **Listing off devices and waking them:** already done on the Pi (`ChromecastSource` lists every configured TV;
  `SpeakerSource` every paired Bluetooth sink; `TvWake`, `CastLink.connect` and `BluezLink` wake or link on a play; the
  `WAKE` control exists). Only the Soundmic's words changed.
- **Row marks:** the `LocalHomeJobs` / `homeJobOf` pattern, copied as `LocalDeviceMarks` / `deviceMarkOf`.

## Done

- [x] The last used device is the destination from every tab, saved across restarts (`PlayOnStore`).
- [x] At home all devices are offered, on or off; away only this phone (`PlayOn.options`, `TargetKind.reachableAway`).
- [x] "▶️ Play on" row under every header; "📡 Play on…" in every item menu; picking a sleeping device wakes it.
- [x] The device's colour on rows, the queue, the mini player and the full player.
- [x] "🔌 Switch Soundmic on, then try again" on the Pi and the phone.

## Open

- **"At home" is "the home server answered and the connection is not metered".** Other Wi-Fi (café, work) counts as home.
  Needs Dewi's call on a tighter signal (the Pi's LAN address from the phone, or a Wi-Fi name).
- Device checks owed: LG TV woken from the picker and from a play with the picker untouched; the LG soundbar linked by a
  play; the Soundmic message with it off; the colour badge on a row playing on the TV.
- Not built: a notification or pill per device; the mini and full player showing a remote device's item (ADR 152's open
  item); Peek on a device (an exception, ADR 108).
