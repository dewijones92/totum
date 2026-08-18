---
title: Listen-mode exit + toggle UX
kind: todo
status: shipped
area: playback
priority: high
requested: 2026-07-24
updated: 2026-08-18
---

# Get out of listen (audio-only) mode + unify the UX

**Ask:** need a way to get *out* of listen-only mode; the UX seems a bit
unfinished / not unified.

Today "Listen (audio only)" on a video drops the video track, but there's no
obvious way back to watching — the listen/watch state feels one-way and
inconsistent.

## Approach

- Make it a clear **two-way toggle**: Listen ⇄ Watch, with the current state
  obvious (icon + label reflect where you are and what tapping does).
- Reachable from the full player **and** the mini player (so you're never stuck).
- Persist/behave sensibly: switching to Watch re-attaches the video at the current
  position (don't restart); switching to Listen keeps audio playing seamlessly.
- **Unify** it: one control component, consistent wording/icon everywhere it
  appears — the "unfinished/ununified" feel is the real bug to fix.

Related: [background-audio-listen-mode](background-audio-listen-mode.md) (audio must
keep playing with the screen off while in listen mode).

**Done when:** you can toggle Listen⇄Watch freely from both players, state is
always clear, and switching doesn't restart playback — verified on-device.

## The "don't restart" promise was not true until 2026-08-18

This doc has said `shipped` while claiming *"switching to Watch re-attaches the video at the current
position (don't restart)"*. The code did not do that: `watch()` called `playVideoQuality(resolved)` and
that parameter defaults to 0, so the player fell back to the resume store — and `seekTo` neither saves
progress nor resets the save tick, so a scrub-then-switch inside the save window lost the seek entirely.

`selectQuality` and `selectAudioTrack` had the same omission. All four switch paths now take one shared
`whereWeAre()`, so a fifth cannot quietly leave it out, and `SwitchingKeepsYourPlaceTest` pins it.

A status of `shipped` on a promise the code never kept is worse than no doc, because it stops anyone
looking. Recorded here rather than quietly corrected.
