---
title: Feed pagination (infinite scroll)
kind: feature
status: shipped
area: video
updated: 2026-10-30
---

# Feeds no longer stop at page one

> ✅ **Rows already loaded never move, 2026-10-30**: default sort As listed, late Shorts held for the next page, and a log line when rows on screen move. [ADR 98](../adr/0098-a-feed-never-moves-rows-already-loaded.md).

> 🐛 **Subscriptions paged the wrong tab, and led with YouTube's picks. Fixed 2026-10-30.** The TV answer has one tab
> per channel after "All"; taking the last token paged the last channel's old videos as page 2, and the feed ended at
> 78 with 16 of the newest 100. Now the selected tab is paged and the "Most relevant" shelf is left out: 96 of 100
> against RSS, 2,700 paged in date order. [ADR 97](../adr/0097-the-subscriptions-feed-is-the-all-tabs-date-grid.md).

Every YouTube feed in the app showed roughly one screenful and then simply ended. It
didn't look broken — a short list looks like a short list — which is why this was the
app's biggest content gap rather than its most obvious bug. YouTube returns a
**continuation token** with each page; the parser was discarding it.

## The seam

`Page<T>` and `PageToken` in **`:lib:common`** — one shape for every paged source in the
app, so infinite scroll is written once:

```kotlin
data class Page<out T>(val items: List<T>, val next: PageToken? = null)
```

Three properties earn their place:

- **`PageToken` is opaque.** The app never parses or builds one; a source hands it out and
  takes it back. That's what lets the same type carry a YouTube continuation, an offset,
  or a cursor without callers caring.
- **"No more pages" is an ordinary page** (`Page.last`). This is what makes pagination
  unify across the pillars: an RSS document holds the whole feed, so the podcast side
  returns a last page and needs no special case. No pillar-specific branch anywhere.
- **`append` deduplicates.** YouTube does return overlapping pages, and a duplicate key
  in a `LazyColumn` is a crash rather than a cosmetic problem — so dedup lives in the
  seam, not in each caller.

## Following continuations

`Continuations.find` walks the response and takes the **last** token it finds. Deliberate,
for two reasons learned from the shapes YouTube actually serves:

- It has shipped at least three continuation shapes (`continuationCommand`,
  `nextContinuationData`, `reloadContinuationData`) and still serves the older ones on
  some feeds. A parser keyed to one path silently stops paginating — indistinguishable
  from reaching the end.
- A shelf *inside* a feed carries its own token ("more from this channel"). Taking the
  last one gets the feed's rather than the shelf's.

`InnerTubeClient` now browses a **`BrowseTarget`** — `Id(browseId, params?)` or
`Continuation(token)` — instead of exposing four near-identical methods. A sealed pair
rather than two nullable parameters, because sending both is meaningless: a continuation
already encodes what it continues.

## In the UI

- `LoadMoreOnScrollToEnd` (one shared composable) asks for the next page while four rows
  remain below the fold, so the page usually arrives before the user reaches the bottom.
  Waiting for the true end makes scrolling stutter at every boundary.
- `LoadingMoreFooter` is the footer spinner.
- `VideosViewModel.loadMore()` guards itself: no overlapping requests, a no-op once
  exhausted, and **a failed page keeps its token** so scrolling retries rather than
  permanently ending the feed on one flaky request.
- **Refresh adopts the new token.** Keeping the old one would append pages continuing a
  list the user can no longer see.

## Scope shipped

- The four **account feeds** (Home / Subscriptions / Watch Later / History).
- **Channel tabs** — Videos, Shorts and Playlists.

Everything paged now returns a `Page<T>`, including `FeedResult`, which briefly carried its
own `next` field before being retrofitted. That mattered: "one shape for every paged source"
stops being true the moment a second shape exists, and a parallel field is how that starts.

Still page one, tracked in [`docs/todos/feed-pagination.md`](../todos/feed-pagination.md):

- **search results**
- a **playlist's** own screen, and **related videos** (both drop their continuation
  explicitly, with a comment, rather than silently pretending page one is everything)
- **podcast episode lists** — a no-op by nature, since RSS returns the whole feed

Comments already paged before this work, by their own path.

### Channel tabs: how the three became one

`HttpYouTubeChannel` had three copies of the same request-and-map dance. Collapsing them
into one private `tab()` meant paging was added in **one** place rather than three, and the
same is true in the view model: `TabState<T>` already existed, so it grew `next` and
`loadingMore`, and one generic `pageMore` serves all three tabs.

## Measured on-device

Against live YouTube on emulator-5554, scrolling the Subscriptions feed:

```
[feed] SUBSCRIPTIONS page +30 (had 45)  more=true
[feed] SUBSCRIPTIONS page +15 (had 75)  more=true
[feed] SUBSCRIPTIONS page +15 (had 90)  more=true
[feed] SUBSCRIPTIONS page +15 (had 105) more=true
[feed] SUBSCRIPTIONS page +14 (had 120) more=true
[feed] SUBSCRIPTIONS page +15 (had 134) more=true
```

**45 items before, 149 after six followed continuations — and still more to come.** That
45 was the whole feed as far as the app was concerned. The line is a `Diag` breadcrumb, so
it also lands in crash reports.

**Channel tabs are verified against the wire contract, not on the device.** Reaching a
channel page needs either a signed-in account or a "go to channel" action, and search
results render their own row rather than the shared `MediaItemRow`, so a fresh signed-out
install has no route to one. The `MockWebServer` tests assert the actual requests and
responses instead — that a continuation is sent *instead of* the tab params, and that all
three continuation shapes parse — which is the part that could silently be wrong.

## Tests

- `lib/common/.../PageTest.kt` — `hasMore`, `last`/`empty`, `map` keeping the token,
  `append` concatenating, advancing and deduplicating, blank token rejected, token
  redacting itself.
- `lib/innertube/.../ContinuationsTest.kt` — all three continuation shapes, last-token
  precedence over a nested shelf, absent and blank tokens.
- `lib/innertube/.../HttpYouTubeChannelPagingTest.kt` — against `MockWebServer`: a tab's
  continuation reaches the caller, a later page sends the continuation and **not** the tab
  params, a first page sends the params and no continuation, an empty page carries no token
  even when the response has one, and Shorts/Playlists go through the same path.
- `app/.../VideosPagingTest.kt` — the behaviour that was missing: a token offers more,
  `loadMore` follows it and appends, overlapping pages don't duplicate, exhausted feeds
  no-op, concurrent calls make one request, a failed page retries, refresh adopts the new
  token.
