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

## Consequences

- A video's picture catches up with the sound in several short steps rather than one long one; the sound is unchanged.
- The new files are named `Silence*.kt` so `audio-quality.yml`'s `paths:` run the suite when they change.

## Tests

`HeardSilenceAudioSinkTest` (a 7 s pause on a video is released as jumps of at most 400 ms, seen red first at one 6,960 ms
jump; the same pause with no video is still one jump; each capped jump is announced), `HeardClockTest` (paid over reads,
never backwards, the whole cut arrives, reported once, a seek forgets, the end pays the rest), `SilenceCutStatsTest`.
