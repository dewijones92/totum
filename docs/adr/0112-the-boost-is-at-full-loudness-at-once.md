---
title: The boost is at full loudness at once, not after it has caught up
kind: adr
status: accepted
updated: 2026-10-31
---

# 112. The boost is at full loudness at once, not after it has caught up

- Status: Accepted
- Date: 2026-10-31

## Context

Dewi, 2026-10-31: *"the sound booster … there's a bit like a delay in it … make sure it makes the whole thing loud,
loud, loud, without any delay."* Asked what he hears, he said loudness lags behind: after a quiet bit, or at the
start, it takes a moment to get loud again.

Measured on the LibriVox fixture before the change (effective gain, output over input energy, by time from the first
word):

| | first 100 ms | 100-300 ms | 300-500 ms | settled |
|---|---|---|---|---|
| Auto, item 18 dB down | 0 dB | 0 dB | 14 dB | 25 dB (reached after about 1 s) |
| Auto, item as mastered | 1 dB | 4 to 9 dB | 13 dB (overshoot) | 7 dB |
| Even out, a passage 20 dB down after a loud one | 6 dB | 6 dB | 6 dB | 26 dB (reached after about 1.5 s) |

Four causes, each found by measuring:

1. **The gain started at unity and climbed with a 400 ms time constant**, whatever the estimate said.
2. **The level estimate started at zero** and waited for 20 ms blocks 4x over the quietest before it began; an item
   that began with speech (nothing quieter to compare with) waited 200-300 ms.
3. **The look-ahead limiter smoothed every rise of the gain as a "release"** (250 ms), so a gain that was asked to go
   up, because speech had begun or a passage had gone quiet, was slewed up at the release rate however fast the
   automatic gain moved. This was the largest single lag.
4. **Even out** followed a quiet passage through a 400 ms peak memory, a 400 ms estimate and a 500 ms rise.

## Decision

- **The estimate is unbiased from the first sample**: a running average until its time constant takes over. Once 30
  ms of speech has been measured the gain is brought to what the estimate wants through a 15 ms glide (not a step: a
  step is a click), for the next 300 ms; after that the old 400 ms rise applies (Auto) so the gain does not follow the
  estimate's wobble.
- **The contrast that tells speech from room tone** is a 10 ms block 2x over the quietest (was 20 ms, 4x); the
  500 ms steady-audio fallback stays.
- **The limiter smooths the amount of reduction, not the gain.** It tracks `allowed / asked` (how far below the
  wanted gain the ceiling forces it) with the fast fall and 250 ms release as before, and multiplies the result by the
  gain asked for at that sample. The bound is the same by construction (each term is at or under the sample's own
  `CEILING / |sample|`); a gain that is asked to rise now rises at once.
- **Look-ahead 5 ms to 20 ms**, the most this change allows itself. The longer ramp keeps the dive ahead of a peak
  gentle now that the gain starts higher; a seek still discards it. The processor holds the sound back 20 ms
  (`LoudnessBoostTest` asserts the figure), a constant offset that does not drift.
- **Even out** (ADR 35, parameters changed): estimate 100 ms (was 400), rise 60 ms (was 500), fall 60 ms (was 120),
  peak memory 150 ms (was 400).

The cast path builds the same `BoostingAudioProcessor`, so it changes with it.

## Consequences

- Speech is within 4 dB of its settled loudness from 100 ms after the first word, in both modes, as mastered and 18
  dB down (`RealSpeechLagTest`; `LoudnessBoostTest` repeats the claim on synthetic words for every commit). After the
  change the worst 100 ms window after that point is 0.8 to 3 dB off. A passage 20 dB down after a loud one in Even
  out is within 4 dB from 400 ms (1.5 s before).
- **The first word can overshoot.** The gain is seeded from about 30 ms of speech, which on a soft attack (a breath,
  a consonant) underestimates the level, so the first syllable can be lifted too far and limited for about 100 ms.
  Measured on the fixture's real opening (a -48 dBFS breath, then speech 40 dB louder 400 ms later): the first second
  is 28 dB clean, was 42 dB. `the first second of an item is not squashed` is relaxed from 33 dB to 25 dB, and `an
  item that opens with room tone does not squash its first words` from 45 dB to 35 dB (a squashed first word measured
  29 dB, so both still tell the two apart). The whole minute is still over 40 dB and the settled part over 55 dB.
- **Even out modulates more**: the gain's spread over a loud passage went from 1.3 dB to about 3 dB (100 ms windows).
  That is what riding a level faster costs, and why Auto stays.
- Even out still overshoots after a pause and when a loud passage follows a quiet one (the gain holds the value it had
  for the quiet part until the estimate catches up). That is not lag, was true before, and is untouched.
- The estimate persists across items of one format that play without a flush (gapless), so a quiet item after a loud
  one in Auto still takes about two seconds to settle. Not changed; there is no signal in the processor that tells an
  item boundary from a seek.
- Reports: `boost: gain heads for 24.1dB, from 30ms of speech` marks the moment the gain starts.
