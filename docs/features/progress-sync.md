---
title: Two-way progress sync with YouTube
kind: feature
status: shipped
area: video
updated: 2026-08-16
---

# Two-way progress sync with YouTube

Dewi, 2026-07-25: *"confident that play progress is 2-way synced with YouTube servers or???"*. The
answer then was **no — one-way, and even that unverified**. Both halves are now true and measured.

## The outbound half was real, and is now proven rather than assumed

The spec said this had never been confirmed against YouTube itself, only that we send the right
request shape to a MockWebServer. Confirmed 2026-08-16, on Dewi's own account:

```
app log        [yt-sync] caVJh4jrOxE pos=789.873 -> Success
YouTube        FEhistory → caVJh4jrOxE, top of history, percentDurationWatched: 13
arithmetic     789 / 6253 (1:44:13) = 12.6%  ✓
```

So the pings do credit the account. That check was the prerequisite for building anything inbound —
there was no point reading a position back from a store we were not actually writing to.

## The inbound half, and the one constraint that shapes it

`FEhistory` returns, per tile, a `thumbnailOverlayResumePlaybackRenderer` carrying
**`percentDurationWatched`** — a whole number. Not a position.

That precision is the whole design. On a 1:44:13 video one percent is **62 seconds**, so a remote
position is good to about half a minute, while the local one is exact to the millisecond. Three
consequences, all deliberate:

- **The percentage becomes a position in the parser**, not later. The tile carrying the percentage
  carries the duration beside it, so that is the one place both numbers exist. `AccountProgress`
  carries the duration onward because the position's precision depends on it.
- **`resumeFrom` (`:core:domain`, pure) decides.** Local wins unless the remote is ahead by more
  than one percent of the duration, floored at 60s — one percent being exactly the resolution of the
  number being compared. Blindly preferring YouTube would make resume *worse* on the device you
  actually watch on: our own ping is what put that number there, rounded down on the way, so the
  remote is always slightly behind locally and would throw you back every time.
- **It is a decorator on the store**, not a second lookup. `Media3PlaybackController` asks one thing
  where an item resumes and that stays true. Podcasts fall straight through — YouTube has no opinion
  about them.

## Verified on device

Against Dewi's account, both directions:

```
# the device that did the watching keeps its exact position
resume caVJh4jrOxE at 1699621ms — LOCAL_IS_AS_GOOD [local=1699621 youtube=1688310 of 6253000]

# and a video watched ELSEWHERE, never opened here, resumes where he left it
resume 62HSUsS0ypo at 1654200ms — ONLY_REMOTE [local=none youtube=1654200 of 2757000]
playback  ready after 4507ms at 1656007ms      → started at 27:34
```

The log carries the inputs, not just the outcome, so a surprising resume can be re-judged from a
report without anyone guessing which side won.

## Cost

One request per five minutes, not per play: `FEhistory` answers for every recent video at once, so
asking per tap would put a round trip in front of every play for a number that barely moves. Every
failure is an empty map — resuming from what the device knows is always safe, and an item resuming
locally is a far smaller problem than a screen that will not open.

## Files

- `core/domain/…/ResumeChoice.kt` — `resumeFrom`, the rule and its reasons
- `lib/innertube/…/VideoTileParser.kt` — `watchedPositions`, percent × the tile's own duration
- `lib/innertube/…/history/HttpYouTubeWatchHistory.kt` — the `FEhistory` read
- `app/…/video/AccountResumePositions.kt` — the seam, and the five-minute cache

## Tests

- `ResumeChoiceTest` — 10 cases: only-local, only-remote, remote ahead, remote behind, a lead inside
  one percent, a short item's floor, an unknown duration
- `AccountResumePositionsTest` — the fall-through for anything YouTube never saw, the cache, and a
  failed inbound read still resuming locally
- `VideoTileParserTest` — the positions read out of a **real captured history response** from Dewi's
  account, including the 13%-of-1:44:13 tile the outbound proof came from

**Not covered:** that YouTube itself honours what we send. That was verified by hand (above) and
cannot be a test — it is an assertion about someone else's server.
