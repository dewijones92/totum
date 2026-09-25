---
title: SABR serves nothing at all for some videos, and the app blames the fixture
kind: todo
status: closed 2026-09-25: it is the ANDROID endpoint's ~60 s wall, seen from the fresh stream recovery opens after it; tracked in po-token-minting.md
area: playback
updated: 2026-09-25
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

# Resolved, 2026-09-25: this is the ~60 s wall, seen from the other side

`SabrPlaysAcrossVideoTypesTest` passes in every run read today: 409f75d, a32957a, cccf25e and 8706584.
The line that made this file is still in their logs, **identical to the original down to the byte**:

```
[sabr] giving up on gngPQ771Ahk:251: it served nothing before dying (itag=251 fetches=4 failed=0 ...
       served=0B discarded=195548B (100% wasted) ... mediaTime=147248ms
```

Reading the whole conversation (run 36138351354) shows what that line is. The first stream for
`gngPQ771Ahk:251` works: with the segment-count claim it asks 9,982, 19,964, ..., 49,911 ms, and keeps
about 155 KB each time. At **59,893 ms it is sent nothing new**, the same wall the 97-minute VOD's audio
(979,459 B) and video (13.1 MB, `protection=status=3`) hit at about a minute. Four empty answers end
that stream. Recovery then opens a FRESH one partway through the file (asking 57 s from a byte ratio).
That stream meets the same wall on its first fetch and "serves nothing".

So the fresh stream is not a separate app failure. Both videos refuse the embedded player ("This video
is unavailable"), fall back to the ANDROID endpoint, and that endpoint stops serving after about 60 s of
media. The test passes because it only asks for 10 s. The wall itself belongs to
[po-token-minting.md](po-token-minting.md).

