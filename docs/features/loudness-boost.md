---
title: Quiet podcasts made audible, automatically
kind: feature
area: playback
status: shipped
updated: 2026-09-26
---

# Quiet podcasts made audible, automatically

**Ask (Dewi, 2026-08-07):** *"make the volume booster thing in the app better???? so I can actually
hear quiet podcasts e.g."* — *"there is already some sort of volume booster in the app but it isnt
strong enough"*.

**And then (2026-08-08), on the version that answered it:** *"the volume booster setting thing in the
app causes distortion … i dont want distortion, i want it to allow me to hear things like quiet
podcasts well please … compression?? or sumin????"* — noticeable from **Medium upwards**, on
earphones, on 0.1.371.

**And (2026-09-25):** *"double check and maybe fix the volume boost, make sure it bring any quiet audio
up to loud, dont distortion"*.

This doc covers all four versions, because the mistakes are the interesting part and each one was
caused by fixing the previous one too literally.

## The four versions, and why each was wrong

| | What it did | Why it failed |
|---|---|---|
| **1. `LoudnessEnhancer`** (to 2026-08-07) | Android's platform effect, one flat gain, capped at +12 dB | Too quiet. A flat gain **clips**, so past the point the loudest peak reaches full scale more gain buys distortion, not volume — the +12 cap was roughly the ceiling for a flat gain, not timidity |
| **2. Fixed levels + limiter** (0.1.371) | Our own compressor/limiter, Off/+6/+12/+20/+30 dB | **Distorted.** The gain moved down as slowly as it moved up, so a loud moment after a quiet one was still being multiplied by the full boost for tens of ms while the gain wound down — every sample of it sliced flat |
| **3. Automatic** (to 2026-09-25) | Measures the item and applies the difference, capped at +20 dB, gain falls instantly | Never exceeded the ceiling, but **pinned the first peaks of every loud onset flat against it**: 12-15 flat tops and 1.4-2.1% harmonic distortion in the first 50 ms. And +20 dB left a very quiet recording at about a third of normal loudness |
| **4. Automatic + look-ahead limiter** (current) | The same measurement, capped at +30 dB, with a 5 ms look-ahead limiter that ramps the gain down *before* a peak | — |

## Version 4: a limiter that sees the peak coming

Version 3's clamp acted **at the peak sample itself**: when a sample times the gain would pass the
ceiling, the gain dropped to exactly `CEILING / |sample|`. So every sample on the rising edge of a
sudden loud passage came out at exactly the ceiling (31129, 31129, 31129…). That is a flat top, the
same shape as clipping, and `clipped=0` could not see it because nothing went *over* the ceiling.
Measured on a quiet passage followed by a loud one: onset distortion 2.11%, against 0.01-0.08% once
settled. Speech is mostly onsets.

[`LookaheadLimiter`](../../core/playback/src/main/kotlin/com/dewijones92/totum/playback/LookaheadLimiter.kt)
delays the audio 5 ms and applies, to each sample, the **average** of the limits over the 5 ms
around it. Each of those limits is at or below that sample's own `CEILING / |sample|`, so the
average is too, and the output still cannot reach the ceiling. The gain ramps down over the 5 ms
before a peak and recovers over 250 ms after it. Onset distortion is now under 0.5% and there are
no flat tops. The two new tests fail on version 3 at the step they name.

The delay changes nothing you can hear. It is 5 ms. Nothing is lost or repeated: switching the
boost on holds the first 5 ms back instead of inventing silence, and switching it off releases
them. A seek throws them away, so audio from before the seek never plays after it. Both channels
get one gain, so the stereo image holds.

**+30 dB, and a louder target.** The cap went from +20 dB to +30 dB, and the target from a
mean level of 0.1 to 0.125 of full scale (about 2 dB louder). A recording 30 dB below the target
now comes up to it. The limiter is what makes the extra lift safe, which is exactly what the +20
cap was guarding against.

## "Won't a hard dB cap prevent distortion?"

Dewi's question, and the answer is the whole design. **A ceiling is not a wall the sound bounces off,
it is a knife.** Everything above it gets sliced flat, and those flat tops are frequencies that were
never in the recording. Hard-capping *is* distorting — they are one event described two ways.

What avoids it is turning the gain down **before** the loud part is multiplied, so nothing ever
reaches the ceiling and the waveform keeps its shape, just smaller. That is what a limiter is.

Version 2 did turn the gain down — over 30 ms, at the same rate it turned up. Measured, on a quiet
passage followed by full-level audio: **5,428 samples (123 ms) clipped at Max and 2,844 (64 ms) at
Medium**, per transient. Which is exactly where he said he heard it.

Version 3's fix was an asymmetry, the basic trick of a limiter: the gain **falls instantly and
recovers slowly**. Because the fall is clamped per sample to `CEILING / |sample|`, the output is
bounded by construction — `|sample| × (CEILING / |sample|) = CEILING`. Not "rarely clips": **cannot**.
That is why `clippedSamples` is reported, and why a non-zero value in a report is a broken assumption
rather than loud audio.

The per-sample clamp was supposed not to modulate the waveform, because the gain could only *rise*
at the slow rate. That held once the gain had settled, and failed at every onset — see version 4
above, where the fall itself now happens over the 5 ms before a peak instead of at it.

## Automatic, rather than a number you pick

Dewi chose this himself over keeping the fixed steps: *"Auto — make everything the same loudness"*.
He was right for a reason worth writing down — **the number was the problem**. Picking a level by ear
per item means routinely asking for more gain than the audio needs, and gain you did not need is
exactly what sounds squashed and over-driven. Measuring the item and applying the difference cannot
over-ask.

So there is nothing to tune. The control is **Off / Auto**.

- It measures a slow average of the item's level and applies exactly the gain that brings it to a
  comfortable target.
- **It never turns anything down.** Quieter-than-expected is a surprise nobody asked for.
- **It never applies more than +30 dB.** It was +20 dB (Dewi, 2026-08-08: *"trade some maximum
  loudness for naturalness"*) until he asked on 2026-09-25 for any quiet audio to come up loud; the
  look-ahead limiter in version 4 is what makes the extra lift clean.

An old stored level (`LOW`…`MAX`) is migrated to `AUTO`, not to `OFF` — silently switching the boost
off during an upgrade would be the app changing a setting nobody touched, which is
[explicitly ruled out](../todos/settings-only-change-when-asked.md).

## The mistake that is easiest to make twice: an absolute noise floor

The level estimate has to ignore pauses, or silence between sentences drags the average down and
winds the gain up, so every gap ends in a blast. Versions 2 and 3 both did that with a **fixed**
threshold at about −45 dBFS, and it is wrong in a way that is invisible until you test it:

**a recording peaking at −46 dBFS sits entirely underneath the floor, is measured as *nothing*, and
receives no boost at all** — the quietest podcasts, which is the whole point of the feature. Caught
by `the quieter the recording, the more gain it gets`, which produced `[1.0, 6.7, 1.9, 1.0]`: the
*quietest* input got the *least* gain.

Lower the floor to admit that speech and it admits tape hiss too. There is no absolute number that
separates them, because the difference between quiet speech and loud hiss is not a level — **it is a
level relative to the rest of the recording**. So the gate now follows the content: 20 dB below its
recent peak (which rises instantly and decays over 2 s), with a −66 dBFS absolute floor that only
rejects digital silence and dither.

The old **noise-floor taper** is gone with it. It existed to stop a fixed +30 dB turning hiss into a
roar, and it was a downward expander that ate the quiet ends of words. Automatic gain removes the
need: the lift is proportionate to the item, so its noise floor rises with its speech, exactly as it
would if you turned the volume up.

## The first second of every item was squashed (found 2026-09-26)

Found by measuring a public-domain LibriVox reading second by second, not by ear or by a report.
Cleanness here is how far the output departs from a clean change of gain, measured per 10 ms window
(the residual after fitting the best single gain to each window, in dB below the signal):

| Second of the item | 1 | 2 | 3 | 4 | after 5s |
|---|---|---|---|---|---|
| Before | 22 dB | 30 dB | 56 dB | 67 dB | 62 dB |
| After | 42 dB | 31 dB | 58 dB | 67 dB | 62 dB |

The whole minute went from 34 dB to 45 dB. **The cause:** recordings open with room tone, and the
booster, which judged "is this content?" only against the loudest thing heard lately, measured that
room tone as the item. It asked for +22 dB before the first word, so the limiter had to crush the
first words by up to 14 dB while the gain slid back down to the +8.5 dB it should have had. The
report from Dewi's phone at 06:10 that morning shows exactly that shape: `auto gain 16.9dB …
limiter down to -11.8dB` two seconds in, `10.0dB … -0.3dB` four seconds later.

**The fix:** the booster does not start measuring until it has heard contrast, a 20 ms block four
times (12 dB) louder than the quietest one so far, or until half a second of audio has passed with
none, so a genuinely steady recording is still boosted. The moment it starts is logged as `boost:
measuring from Nms in: …` with which of the two it was.

**Tried and rejected, measured:** bounding the warm-up gain by the loudest peak heard (no effect:
room tone's peak is small); letting the gain fall fast while learning (worse, 25-27 dB: the gain
then chases syllables, which is modulation); re-learning when a sound 30x the estimate arrives (never
fired, the room tone was still inside the warm-up); requiring 200 ms of contrast rather than 20 ms
(slightly cleaner, 49 dB overall, but the start then audibly fades up).

**What is left:** a lead-in with a breath in it. The breath is contrast, so the booster learns from
the breath and the next second is still squashed (31 dB above). The real fix is for the booster to
estimate from further ahead, the way the silence cutter now judges each frame half a second ahead;
not done yet.

## The seam

Unified by construction — it is in the **sink's processing chain**, so every byte of audio the app
plays passes through it: both pillars, every screen, streamed or from disk.

| Piece | Where | Why there |
|---|---|---|
| `LoudnessBoost` | `:core:playback` | The arithmetic. Pure Kotlin on a `ShortArray` — no Android, no platform effect, so it behaves identically on every device **and the maths is provable on the JVM** |
| `BoostingAudioProcessor` | `:core:playback` | A Media3 `BaseAudioProcessor` wrapping it, plus the reporting. Only 16-bit PCM is touched; anything else passes through untouched rather than being reinterpreted as samples |
| Chain wiring | `PlaybackService` | The booster sits **after** the silence cutter (`SilenceCuttingAudioProcessorChain(cutter, after = [booster])`), so skip-silence judges the recording itself, not a boosted one |
| `VolumeBoost` | `:core:playback` | `OFF` / `AUTO`, plus `fromStoredName` for the migration |
| `VolumeBoostStore` | `:core:playback` | One setting for the app, moved only by the control |

`LoudnessEnhancer` and its recreate-on-audio-session-change block were removed in version 2 and have
not come back. It was bound to a session id, and a stale one silently did nothing.

## Proving it in the wild

Dewi's standing rule is that a change is done when a report sent from his phone a week later can
settle whether it worked **there**. The question this feature has to answer is the one he raised by
ear, so the report carries the two numbers that decide it:

```
boost: auto gain 30.0dB (level 0.0039) limiter down to -6.2dB clipped=0
```

`limiter down to` is how far the limiter had to pull the gain below the automatic gain since the
last line. A number that is often large means the limiter is working hard, which would be the
place to look if it ever sounds squashed.

`clipped=0` is the claim the design makes, so a non-zero value is the entire diagnosis in one word —
and it is logged as a **warning** rather than a note, because it would mean a broken assumption
rather than loud audio. There is also a warning for the otherwise-silent case where the stream is not
16-bit PCM and is therefore not being boosted at all.

Rate-limited by **change** rather than by clock: the gain settles within seconds and then sits still,
so a well-behaved hour costs a handful of lines. Logging every interval regardless would fill a
bounded report buffer with the news that nothing happened, which has destroyed real evidence in this
app before.

## Tests

| Level | Test | Claim |
|---|---|---|
| JVM | `LoudnessBoostTest` (26) | 2026-09-26 adds: **an item that opens with room tone does not squash its first words** (first second of speech at least 45 dB clean; 29 dB before the fix), and **a quiet item still comes up loud once the boost has learnt its level**. "It settles within half a second" now feeds speech-like words rather than a tone from sample zero, because a steady tone from the first sample is indistinguishable from room tone and is the one input the booster now deliberately waits on; with the warm-up slowed it still fails. |
| JVM | `LoudnessBoostTest` (earlier) | Version 4 adds: **a sudden loud passage keeps its shape** (under 0.5% distortion at the onset and no flat tops; 2.11% on version 3), **a recording 30 dB too quiet comes up as loud as one at the target** (a third as loud on version 3), both channels get one gain, and switching it on and off mid-stream loses and repeats nothing. And, from before: **Not distorting:** a sudden loud passage after a quiet one clips zero samples, so does six alternating bursts, `clippedSamples` stays 0, nothing wraps. **Making quiet things audible:** a very quiet recording is lifted >8×, an already-loud one is left within 5% of untouched, gain falls monotonically as the input gets louder, nothing is ever attenuated, the cap holds at +30 dB, and it settles within half a second rather than swelling. **Not sounding processed:** a steady tone stays steady within 5%, a pause between sentences does not move the gain, the gap is not lifted more than the speech, quiet speech is amplified rather than gated, silence stays silent, OFF is bit-exact, and an old stored level migrates to AUTO |
| Instrumented | `BoostingAudioProcessorTest` (7) | Version 4 adds that a seek forgets the 5 ms held back for the look-ahead. And, from before: The plumbing Media3 actually drives: the whole input buffer is consumed, samples are read **little-endian** (proven by sign correlation — a byte-swapped read would sit near 50%), OFF passes bytes through, a non-16-bit format is left alone, an empty buffer is fine, and the setting can change mid-stream |

**Why version 2's tests did not catch its bug, which is the lesson worth keeping.** All eleven of
them used a **constant** tone, so the gain had always finished settling before anything was measured
— and the defect only exists during a *change* of level. The clipping test even permitted 5% of
samples pinned to the rail, and the real clipping came to 3%: it passed by squeaking under a bar that
should never have been above zero. Real speech is quiet and then somebody laughs. The tests now lead
with that, and assert exactly zero.

### Honest caveat

What is verified is the arithmetic and the plumbing, by measurement. What cannot be verified from
here is how it *sounds* to Dewi on his earphones — that needs ears. The numbers say a −40 dBFS
recording comes up about 18× with not one sample clipped, and that a properly-mastered one is left
alone. On real recordings (an audiobook, a comedy podcast, a TTS narration) turned down by 12, 24
and 30 dB, each comes back within 0.5 dB of its mastered loudness with 0 clipped samples and no
flat tops, the limiter pulling more than 3 dB on at most 2.1% of samples; at 36 dB down the +30 dB
cap leaves it about 6 dB short (measured 2026-09-26). If it is still not loud enough in practice, the honest lever is the target level or the +30 dB
cap, and the look-ahead limiter is what makes raising either safe to try.
