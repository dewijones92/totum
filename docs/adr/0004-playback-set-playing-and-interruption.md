---
title: Playback gets setPlaying and an interruption
kind: adr
status: accepted
updated: 2026-10-01
---

# 4. Playback gets setPlaying and an interruption that resumes only what it paused

- Status: Accepted
- Date: 2026-10-01

## Context

`PlaybackController` had only `togglePlayPause()`. Anything outside playback that wants it paused
had to read the state and toggle, and a toggle issued late can start playback rather than stop it.
A break also has to put things back as they were, without resuming something the user had stopped
on purpose in the meantime.

## Decision

- The port gains `setPlaying(wanted: Boolean)`. Media3's `togglePlayPause` and `setPlaying(false)`
  share one pause path (`pauseNow`, which also saves progress).
- `PlaybackInterruption` (in `:core:playback`) pauses on `interrupt()` and, on `release()`,
  resumes only if all three hold: it was the one that paused, the same item is still current, and
  nobody pressed play in between (it observes the state stream to see that).
- The port is one function over detekt's limit for an interface, so it is suppressed there: it is
  the app's single playback seam, and splitting it would make two.

## Consequences

- Both pillars get an explicit pause and resume, through the one seam.
- `StateFlow` conflation could hide a very fast play-then-pause during a break; `release()` then
  resumes. That is accepted as rare and harmless.
