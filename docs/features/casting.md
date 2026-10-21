---
title: Casting
kind: feature
area: playback
status: prototype
updated: 2026-10-21
---

# Casting

Cast (the button in the player's top bar) sends what is playing to a Google Cast device. Totum stays the brain: the TV
plays a stream the phone serves, so Totum's queue, autoplay, boost and position carry on as they do on the phone
([ADR 68](../adr/0068-cast-a-stream-the-phone-serves.md)).

- **Video:** YouTube's original video packets, relayed by the phone untouched (never re-encoded).
- **Audio:** decoded on the phone, through the same boost as local playback, re-encoded to AAC in 4 s segments.
- **Seeking** with the TV remote works; **next** on the TV remote moves Totum's queue; the TV moves to the next queue
  item by itself at the end of one.
- **Stop casting** hands back to the phone at the TV's position, picture and all.

Not yet: skip silences while casting, speed on the TV, downloaded videos' picture (sound only), formats picked for the
TV, live streams, a warning before a format the TV cannot play.

Diagnostics: every decision logs under `[cast]` (`dewidebug`): the manifest URL and what it carries, each request the
TV makes, audio production from which segment and why, the boost changing mid-cast, the receiver dropping an item and
whether that was its end, `back on the phone 5000ms: rendered=…` after a cast, and `video frames in the last 30s` while
a video plays locally.

Bench: `~/claude-tasks/totum-cast-bench/` (mDNS replay in the emulator, the Pi hop, receiver and webcam checks).
