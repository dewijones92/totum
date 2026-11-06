---
title: A Pi copy casts with its picture
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# A Pi copy casts with its picture

Report 20261103T201431: The Matrix (HEVC 1080p) cast as sound only. Proven 2026-11-05: the compiled `pictureAt()`
returns UNKNOWN for `…/fetch/files?id=torrent…` (no extension) and `CastStreams.mayCarryVideo` (~517) then sends
`video=none` with no log; the LG plays HEVC from a no-extension link. Fix: carry the picture/file name with the home
copy (`HomeCopy` already has `audioOnly`; `playedFromHome` forces `PlayHandle.Podcast`), probe UNKNOWN rather than
drop it, and log the no-picture reason. No test covers `mayCarryVideo` yet.
