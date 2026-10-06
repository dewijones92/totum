---
title: Playback gets setPlaying and an interruption
kind: adr
status: accepted
updated: 2026-10-06
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
- **Continue Totum (2026-10-06):** Dewi chose to resume the current item during the walking
  break. This is an explicit play request, not the automatic release of an interruption:
  the Android adapter opens Totum and calls the same controller's `setPlaying(true)`, without
  reloading or seeking. The existing break state machine emits this effect instead of pausing,
  and keeps its walking proof and timer. It never acquires an interruption hold, so completing
  or cancelling the break cannot override a later manual pause. Language practice and Just walk
  keep their existing pause/release behaviour.

## Consequences

- Both pillars get an explicit pause and resume, through the one seam.
- `StateFlow` conflation could hide a very fast play-then-pause during a break; `release()` then
  resumes. That is accepted as rare and harmless.
