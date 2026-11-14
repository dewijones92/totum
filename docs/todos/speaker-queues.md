---
title: 🔈 A second queue on the Pi's speakers
kind: todo
status: superseded 2026-11-07 by the Pi playing everywhere (ADR 152): folded into `TargetDeck`; Pi-side tests exist (`TargetDeckSpeakerTest`); not checked on a speaker from this doc
batch: A (2026-11), phase 6: deploy the Pi side and build the phone side
area: playback
updated: 2026-11-14
---

# 🔈 Speaker queues on the Pi

Dewi, 2026-11-01: *"it works great where I can shuffle Vivaldi on my one Bluetooth speaker, but I want a separate queue
… I can control this LG soundbar via Bluetooth … on my LG soundbar queue, I want to shuffle all Bluey"*.

A phone streams media to one Bluetooth speaker at a time, so the second queue plays on the home Pi, which is already
paired with the LG S65Q soundbar and the dprofy Soundmic (memory: pi-bluetooth-audio).

Decided (AskUserQuestion, 2026-11-01):

- **Any Pi speaker, picked each time**; each speaker has its own queue.
- **Same menus plus a speaker switch**: Play / Shuffle all / Queue can go to "📱 This phone" or "🔈 <speaker>"; the
  speaker's queue gets its own view in Queue with play/pause/skip/volume and what is playing.
- **The sound of anything**: YouTube, music, podcasts, audiobooks and torrents, as audio.

## Progress

- ✅ The Pi side ([ADR 116](../adr/0116-speaker-queues-play-on-the-pi.md)): discovery, link, mpv, the queue on
  `QueueSnapshot`, routes, container. Verified on the soundbar 2026-11-01.
- ✅ The phone side, as the target chips of [the Pi playing everywhere](pi-plays-everywhere.md) (ADR 152): chips in Queue,
  Play/Queue/Shuffle all go to the chip being looked at, a colour per speaker. Not built: the mini-player pill,
  per-target notification, and Home Assistant calling Totum (dot-files).

Whole-batch review (2026-11-06): folded into [the Pi playing everywhere](pi-plays-everywhere.md) — the phone gets one "📡 Play on…" picker, not a separate speaker switch; `SpeakerDeck` becomes the target-agnostic queue.

## Status correction (2026-11-14)

The status here said "built 2026-11-07, Batch A (tests owed)", which read as a live item with its own tests owed. It is
closed: the separate speaker queue was folded into the target model ([ADR 152](../adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md);
`SpeakerDeck` became the target-agnostic `TargetDeck`, `/speakers` became `/targets`), and the speaker behaviour is
covered by `TargetDeckSpeakerTest` in `:cli`. What remains open for speakers lives in
[pi-plays-everywhere](pi-plays-everywhere.md) and [play-on-device-picker](play-on-device-picker.md), not here.
