---
title: Live streams play at 1x with no silence skipping
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Live streams play at 1x with no silence skipping

Report 20261104T171409 (0.1.852): two lives played at the user's 2x with skip-silence, outran the live edge and
"gave up buffering" after 30 s and 24 s. The live manifest route was also logged as "the torrent server".

Decided 2026-11-05: live always plays at 1x, no silence cutting; the silent-tail end is already off for LIVE
(`MusicPlaysPlain.kt:19`), consistent with music and audiobooks.

Reuse: `MusicPlaysPlain.tuningFor`/`skipsSilence` (core/playback); `Media3PlaybackController.setSpeed` needs a kind guard
so a live session never saves a global speed; KidMode's save/restore pattern (`KidMode.kt:46-55`). Tests:
`MusicPlaysPlainTest.kt:21-39` loops LIVE with the user's choices and must change (seen red first). Fix the route label.

Whole-batch review: no new guard — `setSpeed` already honours `tuning.keepsChanges`; give LIVE the same `ItemTuning` as MUSIC (speed 1, no skip-silence, `keepsChanges = false`) in `MusicPlaysPlain`.
