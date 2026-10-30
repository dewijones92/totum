---
title: The subscriptions feed is the All tab's date grid, paged by its own token
kind: adr
status: accepted
updated: 2026-10-30
---

# 97. The subscriptions feed is the All tab's date grid, paged by its own token

- Status: Accepted
- Date: 2026-10-30

## Context

Dewi, 2026-10-30: *"scrolling down on it, I feel like it misses out subscriptions … check that the first 100 you see
in the browser … is the same as the 100 in the app"*. Measured on totum-api35, signed in to his account, against every
subscribed channel's RSS (1,619 channels, all 200): the app held **16 of the newest 100** videos (Shorts left out).

The TV `FEsubscriptions` answer is 978 tabs: "All" (`selected: true`) and one per channel, each with a
`reloadContinuationData` of its own. Inside "All": a **"Most relevant"** shelf of 14 picked videos in no date order,
then rows of three in date order, then a Shorts shelf (bare ids, no titles or dates). `Continuations.find` took the
**last** token in the body, the last channel's tab, so pages 2–5 were one channel's back catalogue (2022 videos) and
the feed ended at 78.

## Decision

- **`Continuations.find` skips an unselected tab** (`tabRenderer` with `selected: false`), as it already skips filter
  chips, and **a next page (`continuationCommand`, `nextContinuationData`) wins over a `reloadContinuationData`**,
  which reloads the page already shown. Recommended, history, watch later and a channel browse choose the same token
  as before (checked on live responses).
- **The subscriptions feed leaves out the "Most relevant" shelf** (`VideoTileParser.parse(leaveOutShelves)`; the
  request is `hl=en`), so it is the date grid the browser shows. A picked video is still listed where its date puts it.
  Its watched mark is still read.
- Shorts stay out of this feed, as before: the TV answer carries 15 ids with no titles or dates, on the first page only.

## Consequences

- Against RSS: 96 of the newest 100, 182 of 200, 267 of 300; 2,700 paged on the device, all distinct, going back
  steadily about 8 days. Most of what is missed is one channel's batch of about 15 uploads within minutes, and scheduled
  streams RSS dates early.
- The Videos tab paged 15 at a time from 15 to 2,526 in a scroll on the device.
- Each page logs `[feed] left out N tile(s) in shelves named [Most relevant]; M kept`.
- Tests: `ContinuationsTest` (the All tab's token; a next page over a reload) and `HttpYouTubeFeedsTest` (the date grid
  without Most relevant; another feed keeps every shelf), on `subscriptions_tv_sample.json`, a made-up miniature of
  the real shape.
