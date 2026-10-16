---
title: Sort and filter
kind: feature
area: ui
status: shipped
updated: 2026-10-16
---

# Sort and filter

One control on every list header (the sort icon, with a badge counting active filters) opens one sheet: **Sort by**
(as listed, newest, oldest, added newest, added oldest, title A–Z, channel or show A–Z, longest, shortest) and **Show
only** (downloaded or not, length band, music / talk / videos, published today / this week / this month). Decision:
[ADR 66](../adr/0066-one-sort-and-filter-menu-everywhere.md). The text filter (the funnel, [list-filter.md](list-filter.md))
is separate and combines with these.

## Where

| List | Sort | Filters | Added date from |
|---|---|---|---|
| Videos feed, podcast episodes | ✅ | ✅ | — (not offered) |
| Channel page (videos, shorts), podcast page | ✅ | ✅ | — |
| Account playlists | ✅ | ✅ | — |
| Library downloads | ✅ (plus its size orders) | ✅ | `downloadedAt` |
| Queue header (whole Up next, within each group) | ✅ changes the play order, Undo | ✅ view only | `queue_items.addedAtEpochMs` (v28) |
| Queue group ⋮ → Sort this group… | ✅ changes the play order, Undo | — | as above |

## Rules worth knowing

- A queue sort never moves the playing item, and only rearranges each group's items after it; other groups stay where
  they are. Undo is offered for ten seconds and refused once the queue has changed.
- An item whose length or date is unknown does not pass a length or date filter, so a filter never claims something it
  cannot know.
- Paging lists sort what has loaded so far; loading more re-sorts.
- Rows queued before v28 have no added date and sort last under either "added" order.

## Diagnostics

`sort` lines: the order chosen and the one it replaced, the filters set. `queue` lines: which group was sorted by what
with the queue's size and playing item, "sort undone", or "kept: its undo was not taken".
