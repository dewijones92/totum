---
title: 🔈 A second queue on the Pi's speakers
kind: todo
status: in progress
batch: A (2026-11), phase 6: deploy the Pi side and build the phone side
area: playback
updated: 2026-11-06
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
- ⏳ The phone side: the speaker switch in Queue, "where" on play/queue/shuffle all, mini player, notification,
  diagnostics.

Whole-batch review (2026-11-06): folded into [the Pi playing everywhere](pi-plays-everywhere.md) — the phone gets one "📡 Play on…" picker, not a separate speaker switch; `SpeakerDeck` becomes the target-agnostic queue.
