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

- A frame is quiet when every channel is at or below the **cut level**: an eighth of the recording's
  **speech level**, and never more than 1024. The speech level is the 80th percentile of the last
  twenty 20ms blocks that were at least twice the noise floor (the quietest 20ms of the last 5
  seconds, ignoring near-digital silence). **It is PipePipe's rule normalised for loudness**: on a
  normally mastered recording the 1024 cap is what applies, exactly as in PipePipe; on a quieter one
  the same cut is made relative to its own speech, instead of deleting it.
- **Until any speech has been heard, only near-digital silence is cut**, so a quiet recording never
  loses its opening. The cost: room tone before the first word is left in. The level is learned
  afresh after a seek or a new item, and kept across a speed change or a skip-silence toggle.
- **Measured on real recordings** (six-minute excerpts of an audiobook, a comedy podcast with
  music and ambience throughout, and a TTS narration, decoded locally), with hiss added and the
  level turned down. Pause time removed, and speech energy kept:

  | Recording | Totum | A fixed 1024 (PipePipe) |
  |---|---|---|
  | Audiobook, as mastered | 94-97% removed, 1.0000 kept | 98% removed, 0.9999 kept |
  | Audiobook, 18 dB quieter | 84-98% removed, 0.9999 kept | 99% removed, **0.95 kept** |
  | Audiobook, 24 dB quieter | 95-98% removed (0% with heavy hiss), 0.9999 kept | 99% removed, **0.01 kept**: the recording is gone |
  | Comedy podcast, 18-24 dB quieter | 1.1s of 360s lost, 0.9999 kept | 170-310s lost |
  | TTS, as mastered / 24 dB quieter | 92-96% / 92-97% removed, 1.0000 kept | 96% / 99% removed, 0.09 kept at -24 dB |

  The one case left alone is a quiet recording with heavy hiss (24 dB down, hiss at sigma 30):
  turned up to full volume that hiss would sit above 1024, so PipePipe would not cut it either.
- **How the design was arrived at**, because four earlier versions were wrong, each found by an
  adversarial review with a probe. A fixed 1024 deletes quiet speech outright. An eighth of the
  recent peak latched at 1024 after one loud click and deleted a quiet speaker for good (40ms kept of
  300s); so did a floor plus the maximum of recent loud blocks. A floor-based cap then under-cut clean
  masters (72% of pause time on the real audiobook, against PipePipe's 98%), and a quarter of the
  median over-cut quiet ambience. The fraction and the percentile were picked from the table above.
- A pause shorter than **150ms** is left exactly as it was, bit for bit.
- A longer pause becomes **40ms**: 20ms either side of the cut. Those 20ms fade to zero and back,
  so the join cannot click. PipePipe splices hard.

**The cutter runs before the boost**, so it judges the recording rather than the boosted
recording. The other way round, the boost lifts the hiss in every pause over the cut level and
nothing is cut: 119ms of 6000ms of hissy pauses, against all of them now. The chain type puts the
cutter first by construction (`SilenceCuttingAudioProcessorChain(cutter, after = [booster])`).

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
after each flush. The arithmetic is [`HeardClock`](../../core/playback/src/main/kotlin/com/dewijones92/totum/playback/HeardClock.kt),
pure Kotlin and tested on the JVM. Two details matter:

- **A speed change or a skip-silence toggle mid-item flushes the processors**, and a new cutter
  starts at zero skipped. The sink adds only the current count, so until the new stream is heard,
  the position would lose every pause cut so far and freeze for up to the buffer's length. Each flush
  now leaves a checkpoint holding the previous stretch's own cut ledger, so its cuts count exactly
  as they are heard, across any number of flushes (tapping speed twice used to lose them all: 12059ms
  became 4578ms). An empty stretch between two flushes inside one buffer does not overwrite the
  ledger. A seek carries nothing, because the old audio is thrown away.
- **The player is told when a cut is heard.** The sink reports a skip ~100ms after it is *made*,
  and the media session sends positions to the app only on events and every 3s, so the app's
  position (the scrubber, SponsorBlock's skip check, saved progress) lagged behind. The wrapper
  swallows the sink's early report and makes its own when the cut is heard.
- **The last cut of an item must be released before the audio ends.** ExoPlayer declares an item
  ended only when the position has reached its duration. The sink stops being asked for positions
  about 20ms before a trailing cut's release point would have been reached (heard 8299ms against a
  cut at 8300ms), so the clock never jumped, and the player walked the last pause on its own clock
  in real time: 2 seconds added to every item that ends in silence. Once the sink has been handed
  the last of the audio (`playToEndOfStream`), a cut is released up to 100ms early. Found because
  the boost's 5ms look-ahead tipped the race every time: 10.8s with it on, 8.6s off. The skip-silence speed-up path (`SilenceDetectingAudioProcessor`,
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
| `no pause long enough to cut in 60s of audio (cut level 175, noise floor 250, speech peak 1400)` | why nothing is happening: the recording's pauses sit above the cut level |
| `audio underrun #n at …ms: the output ran dry` | a break in the sound, for any reason |
| vital `silence.cut` | pauses and seconds removed so far |

## Tests

| Level | Test | Claim |
|---|---|---|
| JVM | `HeardSilenceAudioSinkTest` (4) | a seek or a new item learns the cut level again, a speed change keeps it; the wrapper's wiring through a scripted inner sink and the real cutter: the sink's early skip report is swallowed and the player told once when the cut is heard; one, two or three flushes in a row give the same position; after `playToEndOfStream` the last cut counts. Each fails when its piece of wiring is removed |
| JVM | `HeardClockTest` (15) | a cut already announced is not announced again after a flush; a flush noticed before the stream restarts is not stranded; a cut carried across a flush is announced when heard; two flushes before either is heard count each stretch's cuts separately; at the end a cut in the last moments is released and one further ahead is not; a cut made but not heard does not move the clock; it moves and is announced once when heard; never backwards; a mid-item flush carries the cut silence until the new stream is heard, never past its start; a seek carries nothing. Mutation-checked: dropping the carry fails two tests, the stock early clock fails one |
| JVM | `BoostAndSkipSilenceTogetherTest` | Media3's real processors in the chain's order, boost on, speech peaking at 3000 with hiss at 300 in its pauses: they are all cut. With the boost first (the committed-before wiring), 119ms of 6000ms |
| JVM | `SilenceCutterTest` (23) | a matrix of quiet recordings (600-3000 peak, Gaussian hiss) after nothing, a click, a cough or 5s of music keeps at least 99% of its speech energy, and has at least 70% of its pause time removed wherever the hiss is 26 dB or more under the speech (fails with the round-3 latch); a quiet recording is cut the way PipePipe would cut it turned up to full volume, and hiss that would sit over 1024 is kept; a music bed well under the speech is cut; a quiet recording that talks straight away keeps its opening; a long cut is not reported as nothing to cut; quiet speech after 5s of loud music, or after one loud click, loses at most its opening word; quiet speech (peaking at 197) is never cut away; a quiet recording with hiss (1000/120) still has its pauses cut; a normally mastered one (20000/800) is cut as PipePipe cuts it; pauses louder than 1024 are kept as PipePipe keeps them; pauses under 150ms untouched; every longer pause becomes 40ms; the sound either side is bit-exact; the join fades to near zero; the skipped count equals exactly what was removed; the same output in any chunk size; stereo is a pause only when both channels are quiet; 1025 is never a pause; a cut counts only once playback reaches it, and keeps counting while a long pause is still being cut; trailing pauses. Mutation-checked: removing the fade fails one test, miscounting skipped frames fails another |
| Device | `SilenceIsReallyCutTest` (8) | WAV, MP3 and stereo AAC podcasts, after a seek, at 2x, a video, and a video whose drawn frames are checked against the sound; a quiet podcast with the boost on is still cut. Every case also asserts the sound broke up at no more than two separate points. Red on the old code at every case (table above), and the break-up guard fails at 4-5 points with a 250ms buffer |

### Honest caveats

- A pause whose hiss would sit above 1024 with the recording turned up to full volume is left in,
  and so is room tone before the first word. A music bed with its own dynamics can count as speech
  and keep pauses in (5-15% removed in a probe, against 99% for a fixed 1024); whether a pause under
  a music bed should be cut at all is a judgement, not a bug.
- Hiss that hovers near the cut level can end a cut early, so a long pause may come out as two or
  three pieces with 40ms fades between them. PipePipe does the same near 1024.
- A video pause is released as a jump in the clock. Frames more than 500ms late make the decoder
  drop to the previous keyframe and decode forward, so on a stream with keyframes seconds apart a
  long pause may show as a short freeze of the picture. The test video has a keyframe every second;
  real YouTube streams have not been measured.
- Pauses much longer than about five seconds can still make the sound break briefly on a slow
  device, while the decoder catches up. That break is logged as an underrun.
- How it sounds is not verified by ear from here. What is measured is the timing, the join, the
  picture and the underruns.
