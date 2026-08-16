---
title: Shorts and live streams in the feed
kind: feature
status: shipped
area: video
updated: 2026-08-16
---

# Shorts and live streams in the feed

Dewi, 2026-08-16: *"I want YouTube Shorts, YouTube live streams, YouTube videos to be all treated
the same … always displayed everywhere but just tagged."*

## What was actually broken (reproduced before anything was changed)

Signed the emulator into his account and counted badges while scrolling:

| Feed | Rows | 🔴 LIVE | SHORT |
|---|---|---|---|
| Subscriptions | 81 | **9** | **0** |
| Home | ~230 | 0 | 1 |

**Live streams already worked** — listed inline and correctly badged. The first scan reported zero,
but that was the *Home* feed, where nothing happened to be live; measuring the wrong feed would have
produced a "fix" for something that was not broken. Shorts were the whole gap.

## Why Shorts never arrived — it is not our filter

The raw TV InnerTube responses, pulled with his own token:

```
FEsubscriptions (TVHTML5)   3.6 MB   45 video tiles   0 reelWatchEndpoint
FEwhat_to_watch (TVHTML5)   921 KB   15 video tiles   2 reelWatchEndpoint
FEshorts        (TVHTML5)   285 B    not served to the TV client
```

YouTube's TV surface does not put Shorts in the subscriptions feed. Confirmed independently: a
subscribed channel's RSS lists a `#shorts` upload that never appears in the TV feed, while five of
its livestreams from the same day do.

## What SmartTube does (asked for by name)

`MediaServiceCore`'s `BrowseService2.getShortsTV()` calls the **same** `FEsubscriptions` TV query
and reads a Shorts **shelf** out of the response, at
`tvSurfaceContentRenderer → sectionListRenderer → a shelf containing shorts`.

Checked our capture at exactly that path: `tileRenderer` items and channel/notification chrome, and
no Shorts renderer of any kind. That is SmartTube's own open bug
[#4278](https://github.com/yuliskov/SmartTube/issues/4278), not something of ours.

Its fallback does not rescue it either. `getShortsWeb()` defaults to `auth = false` — generic
trending Shorts, not yours — and `getSubscribedShortsWeb()` needs the WEB client, which answers
**HTTP 400** to a TV OAuth bearer. Tested directly, and already pinned by `AuthAttachedByIdentityTest`.

**But it pointed at what does work.** SmartTube's `CHANNEL_SHORTS` params
(`EgZzaG9ydHPyBgUKA5oBAA==`) are byte-identical to the `SHORTS_PARAMS` already in
`HttpYouTubeChannel`. Fired at a subscribed channel: HTTP 200, 49 Shorts, no auth needed. The pieces
existed; nothing joined them to the feed.

## How it works now

- **`SubscriptionShorts`** asks the channels a feed page actually showed — capped at **12 channels,
  2 Shorts each**, because this is N requests where the feed was one. Requests run concurrently.
- **`VideosViewModel`** threads them in *after* the videos are on screen, keyed on the feed that
  asked so switching tabs mid-flight cannot drop Shorts into the wrong list. Dewi's call between
  "all at once, slower" and "today's speed, Shorts arrive shortly" — he chose the latter.
- **`interleaveShorts`** (`:core:domain`, pure) spaces them one per five videos. Interleaved rather
  than sorted is not a preference: a Shorts tile carries a title, a thumbnail and a view count and
  **no date at all**, so a chronological merge is not on offer. The videos keep the feed's own order,
  the list never opens on a Short, and overflow is appended rather than dropped.
- **The shelf read is kept too** — `LockupParser.shortsIn` over the already-parsed tree, so a 3.6 MB
  response is not parsed twice. It yields nothing today and is the difference between Shorts
  appearing the day YouTube starts sending the shelf and nobody noticing.

Both pillars are unaffected: Shorts are a YouTube shape, and a podcast feed simply has none.

## Two things found by looking at the screen

**Shorts now carry their view count**, from `overlayMetadata.secondaryText`. Without it a Short in a
feed of videos has an empty fact block under its title, which reads as broken rather than brief. The
channel comes from the caller — we asked one channel, so we know whose it is.

**The channel line was showing the view count on a third of rows** (23 of 55). Pre-existing:
`authorLine()` took metadata line **zero by position**, while every other field in that parser is
matched by **shape**, and `LockupParser` already did it correctly. It was invisible while the three
facts shared one truncating line; [giving each its own line](upload-dates.md) put it on screen.

The first fix then produced a bare `📺 ·`, because YouTube renders the separator between metadata
items as its own line item and a "·" is neither a view count nor a date. **Caught only by looking at
a screenshot** — the scan that had just reported "0 wrong" was still only looking for view counts,
which is the difference between checking the thing and checking the previous failure.

## Tapping one opens the reel (2026-08-16)

Dewi, on whether a Short should open the vertical reel: *"open in a reel sorta view but keep
unified???? i dunno"*.

**The two are not in tension, and that is the answer.** `ShortsReelScreen` already plays through the
one shared `PlaybackController` and the one `PlaybackQueue` — its own header says so: *"Uses the same
playback session as everything else, so closing the reel keeps it playing in the mini player."* It is
a **presentation**, exactly as `FullPlayer` shows a video surface for a video and artwork for a
podcast from a single seam. So a Short can look like a Short without the app growing a second way to
play anything.

Tapping a Short in the feed now opens the reel rather than the ordinary player. `shortsReelFrom`
(`:core:domain`, pure) decides what it contains: **every Short in that feed, in feed order, opened on
the one you touched**. The whole list rather than "from here on", because the Shorts you just
scrolled past are the ones you are most likely to swipe back to — `ReelStart.index` is what puts you
on the right page. A Short that is not in the list (a stale row, a filtered view) opens as a reel of
one rather than refusing.

Everything else in the feed plays exactly as before.

## Verified

On the emulator, against his account, after the change: **18 SHORT badges, 11 LIVE badges, 29 channel
lines and not one of them a view count or a stray glyph.**

Tapping one: `[queue] play-all(19)` — the feed's 19 Shorts became the reel — then
`video size=608x1080`, playing full-screen and vertical.

## Files

- `app/…/video/SubscriptionShorts.kt` — asks the page's channels
- `core/domain/…/FeedWithShorts.kt` — `interleaveShorts`, the spacing rule
- `core/domain/…/ReelStart.kt` — `shortsReelFrom`: what a tapped Short opens, and where
- `lib/innertube/…/VideoTileParser.kt` — the shelf read, and the shape-matched channel line
- `lib/innertube/…/LockupParser.kt` — `shortsIn`, and a Short's view count

## Tests

- `FeedWithShortsTest` — spacing, ordering, dedupe, overflow, a zero interval
- `SubscriptionShortsTest` — which channels are asked, the caps, the author and views, one channel
  failing without costing the rest
- `ShortsReachTheFeedTest` — the ViewModel actually calling both, tagged, and **the videos on screen
  before the Shorts request finishes**
- `ShortsReelTest` — the reel holds every Short in feed order, opens on the tapped one, keeps the
  ones above it, excludes videos, and survives a Short that is not in the list
- `VideoTileParserTest` / `LockupParserTest` — the shelf, the view count, and the channel line that
  is neither a view count nor a separator

**Not covered:** a real captured response containing a Shorts shelf, because YouTube does not
currently send one — that test drives a hand-built body and says so.
