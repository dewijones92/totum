---
title: Search's video hits use the watch URL as their id; everything else uses the video id
kind: todo
status: done — fixed in 94463e75 (database v26); this file said "open" until 2026-10-14
area: search
priority: medium
requested: 2026-10-09
updated: 2026-10-14
---

# One video, two ids

Found while checking shared album links on the emulator (2026-10-09). Feeds, channels, the resolver and
shared links give a YouTube item the bare video id (`Nn_CcTBataE`); search's video hits
(`SearchHit.Video.toMediaItem`, `ui/common/FeedVideoMapping.kt`) give it the watch URL
(`https://www.youtube.com/watch?v=Nn_CcTBataE`). The same video found in search and in a feed is then two
items: queued twice, played-state and progress split, and a download made under one id not found under the
other, so it streams with the file on the disk.

Music items were moved to the video id in the same change (ADR 25, `OneSongOneIdTest`). Videos were left
alone because changing an id orphans history, progress and downloads already stored under the old one, and
that is a decision about shipped behaviour.

To decide: move video hits to the video id (and optionally rewrite stored ids once, keyed by the watch URL),
or accept the split.

## Done (found stale on 2026-10-14)

Fixed the same day it was found, in `94463e75` ("one id per video (DB v26)"): `SearchHit.Video.toMediaItem`
takes the id from the watch URL, and `VideoIdMigration` (run by `MIGRATION_25_26`) rewrote stored watch-URL
ids to the bare id in `play_history`, `downloads`, `playback_progress`, both account-progress tables,
`local_playlist_items` (per playlist) and `queue_items`, keeping the better row where both existed (newer
play or progress, a finished download over an unfinished one). Backups restore through `canonicalItemId`.
Covered by `VideoIdMigrationTest` (instrumented) and `OneSongOneIdTest`. This file kept saying "open",
which put it back in a batch on 2026-10-14 (Dewi chose "rewrite them once", which is what v26 already did).
A sweep of every `MediaItemId(` construction on 2026-10-14 found no other place building one from a URL.
