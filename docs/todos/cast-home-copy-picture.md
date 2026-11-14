---
title: A Pi copy casts with its picture
kind: todo
status: built; tests written
batch: A (2026-11)
priority: high
updated: 2026-11-14
---

# A Pi copy casts with its picture

Report 20261103T201431: The Matrix (HEVC 1080p) cast as sound only. Proven 2026-11-05: the compiled `pictureAt()`
returns UNKNOWN for `…/fetch/files?id=torrent…` (no extension) and `CastStreams.mayCarryVideo` (~517) then sends
`video=none` with no log; the LG plays HEVC from a no-extension link. Fix: carry the picture/file name with the home
copy (`HomeCopy` already has `audioOnly`; `playedFromHome` forces `PlayHandle.Podcast`), probe UNKNOWN rather than
drop it, and log the no-picture reason. No test covers `mayCarryVideo` yet.

## Built 2026-11-06

`CastStreams.pictureVerdict` replaces `mayCarryVideo`: an address that names no file type is probed (its bytes say),
a sound-extension address and a YouTube address that carries no picture are not, and every session line says
`picture because …` (the probe's own "no picture … sound only" line follows when the bytes say none). Nothing in the
cast path depends on `playedFromHome` forcing `PlayHandle.Podcast` (no `pillar` is read there); the phone's own Listen/Watch
offer for a Pi film still reads `UNKNOWN`, which is a separate item. Carrying the file name from the Pi would need a
server change (`/files` has no name); not done, so the phone and the Pi keep deploying in any order.
Tests to add *(written in part 2026-11-14: see "Tests written 2026-11-14" below for what is done and what is still owed)*: `pictureVerdict` for `…/files?id=torrent…`, an `.mp3`, a YouTube address and a local file.

## Tests written 2026-11-14

`CastPictureVerdictTest` (an address with no file type is probed and casts with its picture, a sound name never gets one, a phone file is judged by its bytes, listening is sound only, a non-video is sound only; the "picture because" reason for each). A googlevideo address is not covered (it needs a network).
