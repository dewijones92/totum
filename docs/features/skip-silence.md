---
title: Skip-silence that actually skips
kind: feature
area: playback
status: shipped
updated: 2026-09-26
---

# Skip-silence that actually skips

**Ask (Dewi, 2026-09-25):** *"get silence skip as good as other apps such as pipepipe that actually
skip silences properly"*.

## It did nothing, and said it was working

Measured before the fix with `SilenceIsReallyCutTest`: 24 seconds of media containing 8 seconds of
sound and 16 of silence.

| Case | Wall time with skip-silence on | Silence removed |
|---|---|---|
| Podcast | 23.8s | none |
| Podcast after a seek | 21.3s | none |
| Podcast at 2x | 12.4s | none |
| Video (the old speed-up path) | 20.7s | 3.3s of 16s |

The log meanwhile said `handling silence by REMOVE_SAMPLES`. The cause was a fight over one switch.
The service called `SilenceSkippingAudioProcessor.setEnabled(true)` directly, but `DefaultAudioSink`
re-applies **its own** skip-silence flag to the chain (`applySkipSilenceEnabled`) at every stream
start, seek, speed change and discontinuity. That flag is the player's `skipSilenceEnabled`, which
Totum never set, so the sink turned the processor back off before it heard anything.

The fix is to use the switch the player provides: the service now sets `player.skipSilenceEnabled`,
exactly as PipePipe does, and the sink applies it through the chain.

## What "properly" means: PipePipe's rule, measured

PipePipe is ExoPlayer **2.18.7** with `setSkipSilenceEnabled(true)`. Both skippers were run on the
JVM against the same synthetic speech (5 words of 250ms, 80ms gaps between words, then a pause).
This is ms of pause kept:

| Pause in the recording | PipePipe (2.18.7) | Media3 1.10 default (what Totum would have used) |
|---|---|---|
| ~100ms, between words | untouched | cut to 59ms, so speech sounds choppy |
| 300ms | 40ms | 103ms |
| 1s | 40ms | 243ms |
| 4s | 40ms | 840ms |

Media3 1.10's processor keeps 20% of every pause up to two seconds and fades the rest to 10%
volume, which is why it cannot match. Its "kept" length is also tied to its detection window, and
that window's length depends on the channel count: it sizes a byte buffer from a frame count, so
stereo engages at a quarter of the configured duration and mono at half.

So Totum has its own cutter, [`SilenceCutter`](../../core/playback/src/main/kotlin/com/dewijones92/totum/playback/SilenceCutter.kt),
with PipePipe's rule:

- A frame is quiet when every channel is at or below 1024, the same level both players use.
- A pause shorter than **150ms** is left exactly as it was, bit for bit.
- A longer pause becomes **40ms**: 20ms either side of the cut. Those 20ms fade to zero and back,
  so the join cannot click. PipePipe splices hard.

## Video: the picture follows the sound, when you hear it

PipePipe removes samples on video too. ExoPlayer adds every skipped frame to the clock, so the
picture follows the sound instead of desyncing. The "~6s of drift over 20s" that made Totum speed
through video pauses instead was measured under a wiring bug (the vararg chain constructor, which
reports zero skipped frames), so it was evidence about the bug, not about the mechanism.

Measured on the emulator with a video whose frames encode their own index, rendered into an
`ImageReader`:

| | Frames drawn | From the sound | From pauses already cut |
|---|---|---|---|
| Cut, clock moved when the cut was **made** (ExoPlayer and PipePipe) | 261 | 97 | 164, flashed through in ~0.4s |
| Cut, clock moved when the cut is **heard** (Totum) | 129 | 113 | about 1 per pause |

ExoPlayer counts a skip the moment the processor makes it, but the audio that precedes it is
still in the output buffer, 250-1000ms from the speaker. So the clock jumps early, and for the last
part of every sentence the picture races through the pause the audio has not reached yet.
[`HeardSilenceAudioSink`](../../core/playback/src/main/kotlin/com/dewijones92/totum/playback/HeardSilenceAudioSink.kt)
wraps the sink and releases each skip only once playback reaches the cut. It works out where that
is from the frames the cutter has output (`HeardCuts`) and from the timestamp of the first buffer
after each flush. The skip-silence speed-up path (`SilenceDetectingAudioProcessor`,
`SilenceStrategy`, `SilenceRacer`) is gone: there is one mechanism for both pillars again.

## A cut must not starve the speaker

Removing a two-second pause means the decoder has to produce two seconds of audio to replace it,
while the output buffer drains. On the emulator it decoded about 5x faster than real time, so a
2s pause took ~400ms to refill, and a 363ms buffer ran dry: `audio underrun` at almost every cut,
heard as a break in the sound. PipePipe has the same default buffer and so the same break.

With skip-silence on, the output buffer is **1 second**
([`SkipSilenceOutputBuffer`](../../core/playback/src/main/kotlin/com/dewijones92/totum/playback/SkipSilenceOutputBuffer.kt)),
which covers pauses up to about five seconds at that decode speed. With it off, the default
buffer is used, because a bigger buffer delays a speed or boost change by the same amount. The
size is chosen when an audio track is created, so switching skip-silence on in the middle of an
item gets the bigger buffer from the next item.

## Proving it in the wild

| Line | Says |
|---|---|
| `skip-silence -> true (player now true)` and `the player's skip-silence is now true` | the setting reached the player and the player took it |
| `sink applied skip-silence=true` | the sink applied it to the chain, the step that used to undo it |
| `audio output buffer 1000ms (skip-silence=true)` | the output buffer that was chosen |
| `cutting pauses over 150ms to 40ms (rate=… ch=…)` | the cutter is active for this stream |
| `cut pause #1 (removed 1960ms; 1s removed so far)` | first cut, then every 100th |
| `cut #1 heard 1036ms into the stream: clock moved on 1960ms when it was heard, not when it was cut` | the clock correction is working |
| `this stretch: cut 8 pause(s), removed 15687ms from 24000ms of audio` | per stream, at every seek or item change |
| `no pause long enough to cut in 60s of audio (quietest 50ms peaked at 1400, cut level 1024)` | why nothing is happening: the recording's pauses sit above the cut level |
| `audio underrun #n at …ms: the output ran dry` | a break in the sound, for any reason |
| vital `silence.cut` | pauses and seconds removed so far |

## Tests

| Level | Test | Claim |
|---|---|---|
| JVM | `SilenceCutterTest` (12) | pauses under 150ms untouched; every longer pause becomes 40ms; the sound either side is bit-exact; the join fades to near zero; the skipped count equals exactly what was removed; the same output in any chunk size; stereo is a pause only when both channels are quiet; 1025 is never a pause; a cut counts only once playback reaches it, and keeps counting while a long pause is still being cut; trailing pauses. Mutation-checked: removing the fade fails one test, miscounting skipped frames fails another |
| Device | `SilenceIsReallyCutTest` (8) | WAV, MP3 and stereo AAC podcasts, after a seek, at 2x, a video, and a video whose drawn frames are checked against the sound; a quiet podcast with the boost on is still cut. Every case also asserts the sound broke up at no more than one point. Red on the old code at every case (table above), and the break-up guard fails at 4-5 points with a 250ms buffer |

### Honest caveats

- The cut level is absolute (1024), as in PipePipe. A recording whose pauses carry hiss above that
  level will not be cut; the `no pause long enough` line says so. With the boost on, the cutter
  judges the boosted audio, so boosted hiss can do the same.
- Pauses much longer than about five seconds can still make the sound break briefly on a slow
  device, while the decoder catches up. That break is logged as an underrun.
- How it sounds is not verified by ear from here. What is measured is the timing, the join, the
  picture and the underruns.
