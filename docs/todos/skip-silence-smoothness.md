---
title: Skip-silence as smooth as AntennaPod
kind: todo
area: playback
priority: high
status: superseded 2026-09-26 — see features/skip-silence.md
updated: 2026-09-26
---

# Skip-silence as smooth as AntennaPod

> **The status below was wrong, and this page was unreliable.** Measured on 2026-09-25, the
> sample removal described here **never ran**: the sink re-applied the player's own skip-silence
> flag (never set) at every stream start, seek and speed change, and turned the processor off. A
> 24-second test file with 16 seconds of silence took 23.8 seconds to play. "Shipped" rested on a
> device test that checked the log line naming the strategy, not whether anything was cut. The
> replacement, which cuts pauses the way PipePipe does and handles video without the speed-up, is
> [`features/skip-silence.md`](../features/skip-silence.md). What follows is kept as the record.

Dewi, 2026-08-04: *"make sure the skip silences thing is as smooth as other apps e.g. antennapod"*.

## Why ours was worse, and it was not a tuning problem

AntennaPod is smooth because it **removes the silent samples**. The audio simply gets shorter:
no rate change, nothing to hear at either edge of a gap.

Totum could not do that, because it plays video too. Removing samples shortens the audio stream but
not the video clock, so the picture falls behind — measured at ~6s over a 20s clip. Speeding through
the gap instead retimes audio *and* video together, so it cannot desync. That was the right call.

The mistake was applying the video-safe mechanism to **everything**. Speeding up is audibly worse: a
step from 1x to 4x is heard at both edges of every gap, and each change reconfigures the audio sink,
which is heard as a stutter. Podcasts — the overwhelming majority of what skip-silence is used on —
were paying that cost for a desync they could never have had.

## What it does now

`SilenceStrategy` picks by whether a **video track is selected**, not by pillar:

| | |
|---|---|
| Audio only | `REMOVE_SAMPLES` — Media3's `SilenceSkippingAudioProcessor`, the AntennaPod mechanism |
| Video | `SPEED_UP` — the existing rate change, because nothing else keeps the picture in sync |
| Switched off | `OFF` |

Both processors sit in the sink chain; exactly one is ever active. It re-evaluates on
`onTracksChanged`, because a queue mixes both — the same switch has to mean sample removal for a
podcast and a rate change for the video after it.

Deliberately NOT chosen by pillar: a video played in Listen mode still carries a video track, and a
podcast with cover art does not. What matters is whether something is being kept in sync with the
audio clock.

## The device test earned its place immediately

The first run picked `REMOVE_SAMPLES` **for a video** — the one combination that desyncs the
picture. Cause: the strategy read `player.videoSize`, which is only populated once the decoder has
reported a size, so asking early says "no video" for a video. It now reads the selected tracks,
which is what `hasVideo` has always meant elsewhere.

A unit test could not have caught it: the decision was correct, and the input was wrong.

## PipePipe is smoother in video mode, and the reason was a wiring bug

Dewi asked, 2026-08-04. Yes — and the cause is not that they found a cleverer mechanism. NewPipe and
its PipePipe fork just call ExoPlayer's own `setSkipSilenceEnabled(true)`, which wires the silence
skipper into `DefaultAudioSink` *properly*.

`DefaultAudioSink.DefaultAudioProcessorChain` has two constructors:

```
DefaultAudioProcessorChain(AudioProcessor...)
DefaultAudioProcessorChain(AudioProcessor[], SilenceSkippingAudioProcessor, SonicAudioProcessor)
```

The sink corrects its position from the chain's `getSkippedOutputFrameCount()`. The **vararg**
constructor treats every processor as opaque and builds its own idle skipper, so that count is
always zero — the audio gets shorter and the media clock never hears about it. On audio you would
see it as seek-bar drift; on video you see the picture fall behind.

We were using the vararg one. It now hands the skipper over by name.

**This casts doubt on our own earlier finding.** The "~6s desync over a 20s clip" that justified
never using sample removal on video was measured under this wiring, so it is evidence about a bug,
not about the mechanism. Re-measuring may well show video can use `REMOVE_SAMPLES` too — which
would delete the `SPEED_UP` path entirely and make video as smooth as PipePipe.

That has NOT been re-measured. `SilenceStrategy` still sends video down `SPEED_UP`, which is
correct-but-possibly-unnecessary rather than known-necessary.

## Still to do

- **Listen to it.** The mechanism is now the same one AntennaPod uses, so it should sound the same,
  but nobody has actually heard it on a real podcast with real gaps. That is the remaining check.
- **Re-measure the video desync** now the chain is wired correctly. If it holds sync, `SPEED_UP`
  and the whole `SilenceStrategy` split can go, and video gets AntennaPod/PipePipe smoothness too.
- The `SPEED_UP` path keeps its 4x step and 20-buffer (~500ms) entry threshold. Both were tuned
  against the flapping problem rather than for smoothness, and video is now the only thing using
  them — worth revisiting if it still sounds abrupt.
