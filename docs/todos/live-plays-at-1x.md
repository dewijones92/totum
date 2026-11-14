---
title: Live streams play at 1x with no silence skipping
kind: todo
status: built 2026-11-06, Batch A (ADR 69 amended; the tuning is covered by MusicPlaysPlainTest; the route-label test and a controller-level check are still owed; not seen on a device)
batch: A (2026-11)
priority: high
updated: 2026-11-14
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

## Built 2026-11-06 (code first; tests owed)

`MediaContentKind.LIVE` shares music's `ItemTuning` in `MusicPlaysPlain.kt` (1x, no skip-silence, `keepsChanges = false`);
no new guard, since `setSpeed` already honours it, and a live item's resume rewind is off with it. The loopback manifest
route is named "a live manifest Totum built" (`routeOf`, via `LocalManifests.servesManifest`). The tuning and speed log
lines say "live" rather than "music". `MusicPlaysPlainTest` updated: live now plays plain. ADR 69 amended.

Tests owed: a `PlaybackRouteTest` case for a `http://127.0.0.1:…/live/<id>.mpd` uri (red against the old "the torrent
server"); a controller-level check that a live item ignores a saved 2x and a speed change on it does not save.

## Test status check (2026-11-14)

The status said only "tests owed". Checked against the code: `MusicPlaysPlainTest` (`:core:playback`) carries the live cases
(`tuningFor(MediaContentKind.LIVE, …)` plays plain: speed 1, no skip-silence, no silent-tail end, `keepsChanges = false`), so the
tuning half is covered. Still not found in the tree: a `PlaybackRouteTest` case for a `…/live/<id>.mpd` loopback address (the
"a live manifest Totum built" label) and a controller-level check that a live item ignores a saved 2x and does not save a speed
change. Neither has been seen on a device.
