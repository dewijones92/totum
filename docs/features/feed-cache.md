---
title: The Videos tab opens with something on it
kind: feature
status: shipped
area: video
updated: 2026-07-31
---

# A blank tab on every launch

Asked what else was slower than PipePipe/SmartTube, this came second only to starting a video —
and unlike that one, it was cheap.

YouTube feed videos were **the one listing the app never persisted**. Podcast episodes have had
a table since the beginning; the Videos tab fetched from scratch every launch and showed nothing
until the network answered. Measured 2026-07-31:

```
16:16:10.263  [place] videos entered … videos=0     ← empty screen
16:16:11.442  [subs] … 1593 total                    ← ~1.2s later
```

PipePipe shows yesterday's feed instantly and refreshes behind it. Now so does this:

```
16:31:07.253  [feed] showing 45 cached items for SUBSCRIPTIONS while it loads
16:31:07.523  [place] videos entered … videos=45     ← was 0
```

## The shape

`FeedCache` (`:core:data`) keyed by a plain string, so account feeds and groups share one seam
and nothing in it knows the difference. `RoomFeedCache` over a new `cached_feed_items` table
(v17, purely additive — an empty cache is exactly the old behaviour, which is what the first
launch after upgrading gets).

**Read-then-refresh, never read-instead-of.** `showCached` puts the last-known list on screen
and leaves `loading` true; the network result replaces it wholesale. Three rules the tests pin
down:

- A cache **never overwrites content already showing** — it fills a blank, it does not replace
  something fresher.
- **Only a successful fetch is saved.** A failure must not overwrite a good cache with nothing,
  or the launch after an offline start would be blank again — the very bug this fixes.
- A feed is **replaced, not merged**. Merging would resurrect videos YouTube has since dropped:
  a watch-later item removed elsewhere would reappear and never leave.

## Deliberately NOT on `PlaylistItemColumns`

That contract exists to rebuild a `PlayableItem`, and this table exists to render a feed row: it
needs no playback handle, since one is derived at play time. Sharing the contract would mean
widening four other tables with a `feedKey`, a position and a cache timestamp they have no use for.

⚠️ **The reason originally given here was wrong, and the app paid for it.** It said the contract
"drops duration, view count, upload date and members-only, because the queue and history do not
render them" — but they do, and losing them was a bug rather than a design. View count and both
dates were added in v18 (2026-08-07) after a video page showed them for an item tapped from a feed
and nothing for the same item replayed from the queue. Duration, `sourceUrl` and `membersOnly`
followed in v19 for the same reason: the Library's "Longest first" was a silent no-op, length chips
vanished from every persisted row, and "Go to channel" fell back to a twelve-second yt-dlp
extraction to read one string.

So the split is about SHAPE — a feed row versus a playable record — not about which facts matter.
`ItemFactsSurviveStorageTest` now guards every field on the contract across all four tables.

## The bug the screenshot caught

The first on-device run logged `videos=45` and still showed **skeleton placeholders**. The
render branch was `state.feedLoading -> FeedLoading()`, which hid cached content behind a
spinner — cached items arrive *while* loading is true, which is the entire point of them. It is
now `feedLoading && videos.isEmpty()`.

Worth recording because the log said the feature worked and the screen said otherwise. Only
looking at it found that; the `[feed] showing 45 cached items` line would have read as success
in any report.

## Files

- `core/data/…/feed/FeedCache.kt` — the port, plus `NoOpFeedCache`
- `core/database/…/CachedFeedEntities.kt`, `RoomFeedCache.kt` — table, DAO, mapping
- `app/…/ui/videos/VideosViewModel.kt` — `showCached`, `cacheKey`, save-on-success
- `app/…/ui/videos/VideosScreen.kt` — skeletons only when there is nothing to show

## Tests

`VideosFeedCacheTest` (3): a successful fetch is cached; cached items are on screen **before
the network answers** (with `FakeYouTubeFeeds.deferred` holding the fetch open — the behaviour
is invisible to a test whose network answers instantly); a failed fetch leaves the cache alone.

Writing the middle one surfaced something worth knowing: selecting a feed before the sign-in
check settles gets wiped by its signed-out branch, which resets the feed wholesale. The app
never does that — it only selects once signed in — but a test that does looks like a broken
cache. The render fix above was caught by a screenshot, not a test.
