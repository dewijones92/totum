---
title: A long silence cut on a video is paid out in short clock jumps
kind: adr
status: accepted
updated: 2026-11-13
---

# 163. A long silence cut on a video is paid out in short clock jumps

- Status: Accepted
- Date: 2026-11-13

## Context

Finding 129: with skip-silence on, a video could sit buffering for 7.5 s at one position after a long pause was cut. The
heard clock (`HeardClock`, ADR on "the picture follows the sound, when you hear it") moves the playback clock by the whole
cut at the moment the cut is heard, so the picture has to skip a pause's worth of video in one step, while the video track
itself is never cut.

## Decision

- **Log first.** Every heard cut is counted into one line per 50 jumps or 30 s (and the first): cuts heard and their
  total and longest length, the clock jumps and their total and longest, how many left more to pay, and the video frames
  dropped since the previous line (`SilenceCutStats`). Whether a video track is selected, and the dropped frames, come
  from `SilenceVideoPresence`, an analytics listener that keeps them in volatile fields because the audio sink runs on the
  playback thread and may not ask the player.
- **Then cap.** While a video track is selected, `HeardClock.maxJumpUs` is 400 ms: a heard cut is added to what the clock
  owes, and each position read pays at most 400 ms of it, so a 7 s pause moves the clock in about eighteen steps of at most
  400 ms instead of one. The position never goes backwards, the player is told about every release, the end of a stream
  pays everything owed at once, and a seek or a new stream forgets it. Audio-only, and Listen mode (which disables the
  video track), keep the single jump.
- The cap is per position read, not per second of wall time, so on a phone the eighteen steps happen within a fraction of
  a second. Whether that is enough to stop the stall is not yet known; the periodic line is how the next report will say.
- **Amendment, same day: the cap alone drew the pause.** CI went red on `SilenceIsReallyCutTest` ("a video shows the
  frames of its sound and skips the frames of its pauses"): with the clock owing part of a cut, the picture follows the
  lagging clock, so frames from the pause the sound had already cut were drawn (112 of 133 drawn frames were from the
  sound against a bar of 113, and 109 of 132 on another run; the same test passes with the cap removed, 113 of 128 and 114
  of 129). On the emulator, five runs of the capped build all failed, one run of the uncapped build failed on lost
  start-up frames, and eight runs of the build below passed. The invariant (the picture shows only the frames you hear) is
  shipped behaviour and stays.
- **So the cap drops what it owes.** While the clock owes part of a cut, `HeardSilenceAudioSink` tells
  `SilenceVideoPresence` how much (`cutOwedUs`, zero after a seek, a flush or when it is paid), and the video renderer
  (`SilenceCutPauseVideoRenderer`, a `MediaCodecVideoRenderer` built by `silenceAwareRenderers`) drops a decoded frame
  that falls before the end of that cut (position plus owed, less the renderer's own 30 ms late tolerance) instead of
  drawing it; the last frame is never dropped (`SilenceCutPauseFrames`). Dropped frames are counted into the existing
  periodic line and logged on the first and every 50th. A first version compared a raw presentation time with the renderer
  position and dropped every frame while a cut was owed; it passed six runs but left one burst of the sound with only seven of its fifteen frames, so
  the comparison is now made in the renderer's own timeline, and the log line shows both times without the renderer offset.
- **What this does not prove.** Dropping frames is the renderer's late path, so the decoder still decodes them; whether
  the capped clock stops the 7.5 s stall on a phone is still open and is for the periodic line to say. If it does not, the
  cap and the renderer can both come out and the logging stays.
- **A coupling to know about.** `silenceAwareRenderers` now builds the video renderer itself, repeating
  `DefaultRenderersFactory`'s own settings from Media3 1.10.1 (`parseAv1SampleDependencies` on, late-input threshold
  15 ms, no prewarming and no extension renderers, which the app does not use). A Media3 bump that changes those defaults
  needs this block read again.

## Consequences

- A video's picture catches up with the sound in several short steps rather than one long one; the sound is unchanged.
- The new files are named `Silence*.kt` so `audio-quality.yml`'s `paths:` run the suite when they change.

## Tests

`HeardSilenceAudioSinkTest` (a 7 s pause on a video is released as jumps of at most 400 ms, seen red first at one 6,960 ms
jump; the same pause with no video is still one jump; each capped jump is announced; the renderer is told what is still
owed, zero once paid, never on audio-only, and zero after a seek), `HeardClockTest` (paid over reads, never backwards, the
whole cut arrives, reported once, a seek forgets, the end pays the rest), `SilenceCutStatsTest`, `SilenceCutPauseFramesTest`
(which frames fall in the owed cut, the 30 ms tolerance, nothing dropped when nothing is owed, the last frame kept).
The picture itself is `SilenceIsReallyCutTest` on a device, red against the capped build and green with the renderer.
