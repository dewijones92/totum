---
title: Search's video hits use the watch URL as their id; everything else uses the video id
kind: todo
status: open — found 2026-10-09, not changed (outside the Music tab work)
area: search
priority: medium
requested: 2026-10-09
updated: 2026-10-09
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
