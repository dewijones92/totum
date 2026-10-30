---
title: A feed never moves the rows already loaded
kind: adr
status: accepted
updated: 2026-10-30
---

# 98. A feed never moves the rows already loaded

- Status: Accepted
- Date: 2026-10-30

## Context

Dewi, 2026-10-30: *"scrolling in the home in the videos tab … it's got infinite scroll, but it doesn't work very well …
it sort of reloads the videos from the middle of the viewport down … instead of at the end. It should behave like a
typical infinite scroll."* Asked, he chose: rows already in the list never move; new things only ever go further down.

Three things moved them:

- **The default sort was Newest**, applied to the whole list each time it changed. A feed row's date is parsed from
  "3 hours ago", and live, scheduled and Shorts rows have none, so every page re-sorted what was already there: undated
  rows sank below the new page's dated ones, and Home (YouTube's ranking, not a date order) had each page's recent
  videos sorted into the middle. Home's first page alone came out "b, a, live" for "a, live, b".
- **Shorts arrive a few seconds after a feed** and were threaded one per five rows through what was already loaded.
- **The cached list is replaced by the fresh one** when it lands (seen on the emulator, scrolled two rows down).

## Decision

- **The Videos tab sorts "As listed" by default** (`FEED_DEFAULT_SORT`): the order YouTube gives, which for
  Subscriptions is newest first since [ADR 97](0097-the-subscriptions-feed-is-the-all-tabs-date-grid.md), and for a
  group its merged date order ([ADR 94](0094-a-groups-dates-come-from-the-feed-then-each-videos-own-day.md)). A sort
  chosen in the menu still applies to the whole list, as asked.
- **Late Shorts are held for the next page** and threaded through it (`waitingShorts`); with no further page they are
  added at the bottom.
- **The screen logs any move**: `[feed] videos rows on screen moved: from row N on screen, was [...] now [...]`, with the
  list sizes and scroll, when the rows from the first one on screen down change (`rowsMoved`). A page added below is
  not a move.

## Consequences

- The cached-to-fresh swap still moves rows if you scroll in the second or so before the fresh list lands; the log now
  says when, so a report can show whether it matters.
- Tests: `VideosPagingTest` (a new page never moves the rows already loaded, seen red: the first page was reordered),
  `ShortsReachTheFeedTest` (late Shorts go into the next page; with no more pages, at the bottom; both seen red),
  `RowsMovedTest`.
