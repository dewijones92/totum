---
title: Queue rows without a date, and dates that never age
kind: todo
area: ui/queue
priority: medium
status: fixed 2026-09-06 — dates are anchored instants rendered against a ticking clock; a shared row learns its facts when it resolves
requested: 2026-09-06
updated: 2026-09-06
---

# Queue rows without a date, and dates that never age

Dewi, 2026-09-06: *"some of the queue items don't have a date published label thing, e.g. 2 hours
ago. And this should obviously increase as time goes by."*

## What was wrong — two defects behind one symptom

Measured on `totum-api35` before the fix (`queue_items`): a shared link had **no date at all**
(`publishedText` and `publishedAtEpochMs` both null — a placeholder row knows only its id, and the
resolution that later played it "says nothing about when a thing was published"), and every other
row carried **frozen wording** — `11y ago`, `9y ago` — stored as text at listing time and shown verbatim
for ever. Nothing in the app could make "2 hours ago" become "3 hours ago".

## The fix — one rule for reading YouTube's wording, one for writing ours

- **`PublishedAge`** (`:core:domain`): `parse("2 hours ago" | "Streamed 3 days ago" | "11y ago", observedAt)`
  → an instant; `text(instant, now)` → YouTube's own words and units. Months are 30 days and years 365,
  the coarse units the wording itself uses.
- **Wording is anchored the moment it is observed**: `FeedVideo.toMediaItem` / `SearchHit.Video.toMediaItem`
  take `observedAt`, and `RoomFeedCache` anchors to its own `cachedAtEpochMs`. The instant is what
  persists (`publishedAtEpochMs` already existed on every row table).
- **The instant wins when a row is drawn**, and is rendered against `LocalNow` — one clock provided at the
  root of the app, ticking once a minute (`rememberTickingNow`), read by every list and the video page.
  This reverses the earlier "the source's wording wins" rule on purpose. Both pillars read the same way:
  a podcast episode is now "3 days ago" too, not a calendar date beside a video's relative one.
- **yt-dlp's `timestamp`/`upload_date` reach the app** (`MediaMetadata.publishedAt`, parsed in the bridge),
  and `withStreamFrom` lets a resolution fill a listing that had no date — the one fact that runs the other way.
- **A queue row learns what it plays**: `VideoPlaybackLauncher` reports every resolution to
  `PlaybackQueue.adoptFacts`, which fills only the row's silence (`MediaItem.fillingSilenceFrom`) — a
  placeholder title, a missing date, author or duration — and persists it. A row that knew stands.

## Proven on device (build of 2026-09-06 17:04)

```
queue_items after sharing jNQXAC9IVRw:
  Me at the zoo | pubAt=1114313512000 (2005-04-24, yt-dlp's timestamp)     → row reads "21 years ago"
  Best Celebrity Lookalike… | pubText=2mo ago, pubAt=1783526832693 (anchored) → row reads "2 months ago"
  Animated Short Film "Big Buck Bunny" | pubText=11y ago, pubAt=null           → still "11y ago"
```

The last line is the one deliberate gap: **rows persisted before anchoring existed carry wording and
no instant**, and nothing can date them after the fact — they show their text verbatim until they are
re-listed or re-resolved. The trail says when a row learns something:
`[queue] row <id> learned title/date/author from its resolution` / `<id> resolved; its row already knew everything`.

## Tests

`PublishedAgeTest` (parse both wordings, format, the label grows with the clock, anchoring, filling
silence), `WithStreamFromTest` (a resolution fills a missing date and never overrides one),
`BridgeJsonTest` (timestamp and upload_date), `MediaItemSubtitleTest` (an instant ages against the
clock; wording alone is verbatim; both pillars relative), `PlaybackQueueTest` (a placeholder row learns
title/date/author and persists them; a knowing row keeps its facts).
