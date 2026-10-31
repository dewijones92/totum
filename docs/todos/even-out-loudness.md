---
title: An "Even out" loudness mode that brings quiet passages up to the loud ones
kind: todo
status: built 2026-10-14 — ADR 35; made faster 2026-10-31 — ADR 112
area: playback
priority: high
requested: 2026-10-14
updated: 2026-10-31
---

# Even out quiet and loud

Dewi, 2026-10-14: *"I want a volume boost thing that works better??? so the quiet bits in songs/videos
are the same loudness as the "loud" bits?? maybe use compression??"*.

Today's boost ([loudness-boost.md](../features/loudness-boost.md)) measures each item once and applies one
gain with a look-ahead limiter; it does not change the balance inside an item.

## Agreed

- A **new mode**, beside today's, chosen in settings: "Even out loud and quiet". Today's boost stays.
- A slow level-riding gain (RMS over roughly 0.5–2 s, attack faster than release) toward a target,
  capped, feeding the existing `LookaheadLimiter`; **never lifting near-silence** (a floor), so pauses
  and hiss stay quiet. Both pillars, one audio processor.

## Notes for the build

- Test on real recordings, as the audio-quality suite does (`-Ptotum.audioQualityTests`): a quiet
  passage after a loud one comes within a few dB of it; silence stays silence; no flat tops; and the
  pumping on music measured, not guessed.
- Interplay with skip-silence (it judges levels too) checked on the same recordings.
- Logged: mode, target, current gain, and how often the floor held a gain back (counted, not per event).

## 2026-10-31

Dewi found loudness lagging behind a quiet bit and at the start; the times above were cut and the gain now starts
at once ([ADR 112](../adr/0112-the-boost-is-at-full-loudness-at-once.md)). Still open: Even out overshoots after a
pause and when a loud passage follows a quiet one, which is not lag; and the estimate carries across gapless items of
one format.
