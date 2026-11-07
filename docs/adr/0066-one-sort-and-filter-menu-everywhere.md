---
title: One sort and filter menu everywhere; a queue sort changes the play order
kind: adr
status: accepted
updated: 2026-11-07
---

# 66. One sort and filter menu everywhere; a queue sort changes the play order

- Status: Accepted
- Date: 2026-10-16

## Context

Dewi wanted to sort lists "by different metrics e.g. a group in the queue sort by date desc ? date added desc ??? any
possible filter". His choices: one menu everywhere (queue groups, the whole Up next, channel and podcast pages, feeds,
playlists, Library); orders published ↑↓, date added ↑↓, length ↑↓, title and channel A–Z; filters downloaded or not,
length bands, music / talk / video, published within; a queue sort changes the play order with an undo; the whole Up next
sorts within each group; queue filters are view only.

## Decision

- **One control**: `SortControl` opens `SortAndFilterSheet` (sort radio list, then "Show only" chip rows and Clear
  filters), with a badge counting active filters. Every list header that sorted before uses it (`SectionHeaderWithSort`,
  `ShapeControl`).
- **One sort**: `MediaSort` (as listed, newest, oldest, added newest, added oldest, title, channel, longest, shortest);
  "added" is offered only where the list knows when each item was added (`MediaSort.offered(addedKnown)`).
- **One filter**: `ItemFilters` in `:core:domain`, pure; an unknown length or date does not pass a length or date filter.
  Screens apply it with `filteredByItems`, which reads download states and the clock through the existing locals.
- **Date added**: queue rows remember when they were queued (`QueueEntry.addedAt`, Room v28 `queue_items.addedAtEpochMs`,
  `MIGRATION_27_28`); a moved item keeps its date (`QueueSnapshot.stampedSince`); a row queued before v28 has none rather
  than an invented one. Downloads use `downloadedAt`. Playlists have no reorder, so their order is the add order and
  they need no column.
- **Queue sort is permanent, with undo**: `PlaybackQueue.sortAfterCurrent` permutes each group's slots after the playing
  item (the playing item never moves); the group ⋮ menu sorts one group, the header sorts every group within itself. A
  long snackbar offers Undo, which is refused unless the queue is exactly as the sort left it (`SortUndo`: a drag,
  another sort or a row learning its title in the meantime all refuse it). During a peek or Explore the sort starts
  after the place the queue left (`QueueSnapshot.place`), never above it; so do Play next, the Up next shuffle and the
  group shuffles (second review, 2026-10-16), and a refused Undo says so on the snackbar.
- **Queue filters are view only**: they hide rows; playback still plays everything.
- **Headers never squeeze the title**: the buttons that do not fit beside a title move to a line under it
  (`TitleAndActions`, used by `ScreenHeader` and `CollapsingTitle`); a wrapped collapsing title keeps the old 104 dp by
  sitting its title on the collapsed line. The Queue's title had been squeezed to one letter; "Subscriptions" broke
  mid-word.

## Consequences

- Paging lists (feeds, channel pages) sort and filter what has loaded so far, and page by the rows they show, so a
  filter hiding everything that arrives stops paging after one ask instead of walking the whole channel.
- An item with no date, length or channel sorts after those that have one, in every order.
- Corrected the same day after the batch review: rows from before v28 were being stamped with the moment of the first
  change after the upgrade; only rows new to the queue are stamped now.
- Tests: `ItemFiltersTest`, `SortingTheQueueTest`, `SortingTheQueueCanBeUndoneTest`,
  `AQueueEntryRemembersWhenItWasAddedTest`, `DownloadsMigrationTest.aRowQueuedBeforeV28HasNoAddedDateRatherThanAnInventedOne`,
  `TheQueueTitleIsNeverSqueezedTest`, `AScreenTitleNeverBreaksAWordTest` (both seen failing on the old headers).
- Logs: `sort` (chosen, with the previous order; filters changed), `queue` (sorted which group by what, undone, kept).

## Recorded 2026-10-31

- The queue's sort sheet ticks "As listed" after a sort, by design: a queue sort is an action that reorders the
  queue (with Undo), not a view, so afterwards the queue *is* as listed. Raised by the consistency audit; kept.

## Tried and reverted 2026-11-07: a smaller Queue header

At 1080x1920@480 (360x640 dp) with a long-title mini player the Queue tab showed a third of one row (the chips 46 dp,
a three-line header 157 dp, the offline banner 60 dp and the 242 dp mini player were all pinned). Four rows are not
reachable on that screen with these rows anyway: a row with a five-line title is about 200 dp and even a short one
about 92 dp, against 365 dp between the status bar and the mini player. The coordinator's call (Dewi delegated it) was a
⋮ overflow for collapse all, music/talk, Explore and Clear all (header down to two lines) and the chips and offline
line as the list's first item. It shipped as v0.1.891 and CI's instrumented job went red, so it was taken back out.
What was learned, so it is not tried again blind:

- **Two controls may not share a label.** The header's ⋮ was "More options", the label of every row's ⋮, so tests (and
  TalkBack) that take the Nth "More options" got the header's. Anything new on the Queue needs its own.
- **Anything of height before the first row stalls the reorder library's edge auto-scroll** (the 2026-10-16 note on the
  1dp spacer, ADR 40, is the same fault). Measured with `QueueRowDragTest.holdingADragAtTheBottomScrollsPastTheScreen`
  at 1080x1920@480: the chips and banner as a list item, as a second item after the zero-height one, and as
  `contentPadding` with the block translated by the scroll all stalled; only the block pinned outside the list passes.
  A block hidden once the list scrolls never settled (`QueueTitleSettlesAtAnyHeightTest`).
- **Pinned, with the shorter header, `MultiSelectTest` "in the queue several rows move to the top together…" never reached
  idle at exactly 1080x1920@480** (the main thread was in `waitForIdle`, frame after frame; it passed at 1080x1880 and with
  the old header). The header's height is constant across its collapse (measured), so the cause is not
  `CollapsingTitle`; it was not found. Whoever shrinks the header again must run that test at 1920 first.
