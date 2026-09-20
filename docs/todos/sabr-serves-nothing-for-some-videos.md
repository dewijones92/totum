---
title: SABR serves nothing at all for some videos, and the app blames the fixture
kind: todo
status: open — an APP failure, after being wrongly written off as a dead fixture
area: playback
updated: 2026-09-20
---

# The failure, and the retraction that has to come first

`SabrPlaysAcrossVideoTypesTest.sabrCarriesEveryContentType` is the only failing test in CI run
35525069446 (20 live tests, 1 failed, 1 skipped):

```
SABR resolved these and the player never got a video track, so nothing was decodable:
Ms Rachel (kids) (player error ERROR_CODE_IO_UNSPECIFIED: Source error
  <- SabrGaveUpException: SABR served nothing for gngPQ771Ahk:251; not retrying it)
```

with `fetches=4 failed=0 served=0B discarded=195548B (100% wasted)`.

**An earlier version of this file said the fixture had been deleted from YouTube and the failure was
therefore not ours. That was wrong.** `gngPQ771Ahk` is alive: oembed answers 200, and **CI itself
resolved it in that same run** — `[resolve] gngPQ771Ahk in 20884ms for play — 8 qualities (8
durable)` — which a removed video cannot do. `PlaysAcrossContentTypesTest`, which uses the same
video, passed in the same run.

The mistake was a control failure of exactly the kind this repo's notes keep warning about: my local
`yt-dlp` answered "This video is not available", and I generalised from one vantage point without
checking a second, while a successful resolve of the same id sat in the artefact I was reading. The
quoted failure text was also truncated, dropping `player error ERROR_CODE_IO_UNSPECIFIED: Source
error <-` — the part that identifies it as a playback-stack failure rather than a missing video.

# What it actually is

SABR fetched four times, was refused nothing, and kept **zero bytes of itag 251** while discarding
195,548 — the same 100%-wasted shape as the hour-long stall, on an AUDIO track this time. The video
resolves fine and plays fine by the ordinary route.

So this belongs with `sabr-cannot-seek.md`'s open question rather than in a fixture bin: what makes
SABR hand back an answer with none of the requested format in it. Candidates are the same — which
segments the server chooses to send for the time asked about, and what the app asks for.

# One fixture genuinely has rotted, separately

`YDvsBbKfLPA` (the live-stream case) **is** private now — oembed 403 — and was already recorded as
such in `youtube-requires-attestation.md` on 2026-09-06. It did **not** cause this failure:
`SabrPlaysAcrossVideoTypesTest` filters unresolved fixtures before asserting, so it contributed
nothing and appears nowhere in the run's logcat.

It is still pinned in `WhatSabrWillServeTest`, which nobody has updated. A live stream cannot be a
durable fixture by definition, so that one probably wants resolving at runtime from a known
always-live channel rather than pinning an id that will rot again — **a judgement for Dewi, not a
fix to make quietly.**
