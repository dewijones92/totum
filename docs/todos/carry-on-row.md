---
title: A Carry on row in Library
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# A Carry on row in Library

Dewi 2026-11-06. Half-watched items of every kind, newest first, at the top of Library. Progress rows have no
titles, so join item metadata (play history); reuse `MediaFilter.IN_PROGRESS` and the hub sections.

## Built 2026-11-06

A ▶️ Carry on row tops the Library hub: history joined to the account-merged play states, `MediaFilter.IN_PROGRESS` and
`isNearlyFinished` reused, 🙅 Not for me respected. Detail: [carry-on-row](../features/carry-on-row.md),
[ADR 137](../adr/0137-carry-on-is-play-history-joined-to-progress.md).

My calls: music and live streams are left out (nothing to resume; the todo said "every kind" and this is the one
exception, recorded in the ADR); the row has 12 cards; a tap queues through `playAll` like the History page rather than
peeking.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`CarryOnTest` (`:core:domain`, 10: history order, unplayed and played and unknown left out, the finished tail by the
state's length and by a short item's last tenth, the item's own length when the state has none, no length anywhere,
music and live left out, the 12 cap, the state carried for the bar), `CarryOnViewModelTest` (the history without
not-for-me items and following the list, a tap queueing a run of one), instrumented `BatchAScreensTest` (the row's
title, each card's words and tap, absent when empty). Seen failing with the live exclusion removed.

Still owed: instrumented, the progress bar and the long-press item menu.
