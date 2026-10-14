---
title: An "Even out" boost rides the level passage by passage
kind: adr
status: accepted
updated: 2026-10-14
---

# 35. An "Even out" boost rides the level passage by passage

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: quiet bits of songs and videos should be as loud as the loud bits, "compression??". The
existing boost (`VolumeBoost.AUTO`, [loudness-boost.md](../features/loudness-boost.md)) measures an item's
level over seconds and applies one slowly drifting gain with a look-ahead limiter, so the balance inside an
item is kept. He chose a new mode beside it rather than replacing it.

## Decision

- `VolumeBoost.EVEN_OUT`, shown as **Even out** next to Off and Auto; stored and restored like the others.
- Same machinery as AUTO (`LoudnessBoost`, the relative and absolute silence gates, `LookaheadLimiter`),
  tuned to follow passages: the silence gate's memory of the recent peak fades over 400 ms instead of 2 s
  (so a quieter passage starts being measured within half a second); the level estimate settles over
  400 ms instead of 2 s; the gain falls over
  120 ms and rises over 500 ms; and it may bring a loud passage down by up to 6 dB, where AUTO never turns
  anything down. The cap stays +30 dB.
- Near-silence is never lifted: the gates keep pauses and digital silence out of the measurement, so the
  gain holds at the speech either side.

## Consequences

- A 3-second quiet spell between loud passages comes within 2x (6 dB) of them (`EvenOutLoudnessTest`); with
  AUTO the same spell stays more than 4x quieter. AUTO does lift a long quiet stretch, after about 4 s; the
  new mode's difference is the short ones.
- Music loses some of its dynamics in this mode, by design; AUTO remains for that.
