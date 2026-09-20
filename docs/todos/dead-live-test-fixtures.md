---
title: Two SABR live-test fixtures no longer exist on YouTube
kind: todo
status: open — the CI failure they cause is NOT an app defect
area: testing
updated: 2026-09-20
---

# A test that fails because somebody deleted a video

`SabrPlaysAcrossVideoTypesTest.sabrCarriesEveryContentType` is the only failing test in CI run
35525069446 (20 live tests, 1 failed, 1 skipped). It fails on:

```
SABR resolved these and the player never got a video track, so nothing was decodable:
Ms Rachel (kids) (SabrGaveUpException: SABR served nothing for gngPQ771Ahk:251; not retrying it)
```

Checked directly with yt-dlp, 2026-09-20:

| fixture | meant to exercise | state |
|---|---|---|
| `jNQXAC9IVRw` | 19-second clip | ✅ "Me at the zoo" |
| `uSMGENDH_QI` | 97-minute VOD | ✅ "Cosmic Dawn" |
| `aqz-KE-bpKQ` | Big Buck Bunny 4K60 | ✅ live |
| `gngPQ771Ahk` | kids' content | ❌ **"This video is not available"** |
| `YDvsBbKfLPA` | live stream | ❌ **"Private video"** |

So **two of the five fixtures have gone**, and the app is being blamed for it. This is the
[[never-assert-someone-elses-policy]] shape: a test whose failure depends on a third party's
decisions is a monitor of that third party, not a guard on this app.

## Why it is not simply an `assumeTrue`

Because that is the trap this repo already fell into: in August a real breakage was reported as a
skip and shipped, and `tools/ci/live-test-via-home.sh` now carries a whole paragraph about it. The
distinction that matters is **"the fixture is gone"** versus **"the app cannot play an available
video"** — the first says nothing about us and should be loud and obviously separate; the second
must stay red.

## What a replacement needs

Not just any two videos. The pair were chosen to exercise content TYPES:

- **kids' content** — YouTube treats "made for kids" differently (no comments, restricted playback
  surfaces), which is the point of having one.
- **a live stream** — no `clen`, no duration, an open-ended manifest, which is the case
  `LiveStreamPlaysToItsEndTest` also cares about.

Pick replacements deliberately against those two properties, and prefer something durable —
`jNQXAC9IVRw` and `aqz-KE-bpKQ` have survived because they are famous. A live stream cannot be
durable by definition, so that one probably wants resolving at runtime from a known always-live
channel rather than pinning an id that will rot again.

**Decision for Dewi:** which two videos, or whether the live case should resolve a stream at runtime
instead of pinning one. Not chosen here because it is a judgement about what the suite should cover.
