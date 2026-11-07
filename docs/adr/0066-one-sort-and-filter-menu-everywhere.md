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

## Recorded 2026-11-07: the Queue header keeps five actions and one overflow (the coordinator's call, Dewi delegated it)

At 1080x1920@480 (360x640 dp) with a long-title mini player the Queue tab showed a third of one row: the target chips
(about 46 dp), a header of three wrapped lines of actions (157 dp), the offline banner (60 dp) and the mini player (242 dp)
were all pinned. The shared header, `TitleAndActions`, was **not changed**: Videos, Podcasts, Music and Library headers
look exactly as before (compared on the emulator at 1080x1920 and 1080x2400). What changed is the Queue's own list of actions:

- The header keeps **Send to…, filter, sort, jump to what's playing and shuffle** (jump and shuffle need no menu: the queue
  is long and they are the ones used while scrolled). **Collapse / expand all groups, the music/talk switch, Explore and
  Clear all** moved into one ⋮ (`QueueHeaderMenu`: a `DropdownMenu` of the shared `MenuItem`, the same pattern as a
  group's ⋮ and the playlist page's), so the header wraps to two lines, not three, at 360 dp. The mini player and the full
  player still carry the music/talk switch and Explore beside the transport (ADR 42), so those two stay one tap away
  wherever you are. Clear all keeps its hold-to-confirm.
- The target chips and the offline banner are the **first list item** (`QUEUE_TOP_KEY`, still one row above the queue
  rows, so `ROWS_ABOVE_THE_QUEUE` is unchanged). They scroll away with the rows, and the queue's own jump on open scrolls
  past them whenever the playing row does not fit, so the first screen is the playing row, not the furniture. The poll of
  the Pi's targets moved out of `TargetChips` (`WatchTargetsWhileShown`, in `QueueScreen`), because a list item that scrolls
  off would have stopped it. A remote target's queue keeps the chips pinned: it has no list header to make room for.
- The mini player was left alone (its controls under the title are Dewi's decision, and its title may not be shortened).

Result on the emulator, 1080x1920@480, long-title podcast loaded: the list area went from about 60 dp (a third of the
playing row) to about 254 dp (the whole playing row and the top of the next). **Four rows are not reachable on that screen
with these rows**: one queue row with a five-line title is about 200 dp and even a short one is about 92 dp, while the
whole area between the status bar and the mini player is 365 dp. The 1080x2400 screen (no mini player loaded) shows three and a half rows.

Tests: `TheQueueHeaderKeepsEveryControlTest` (the five stay on the header, Clear all is only in the menu, every moved
control works from it), `WhatSitsAboveTheQueueRowsScrollsAwayTest`, `ScreensKeepEveryControlTest` (Clear all now opens the
menu first).
