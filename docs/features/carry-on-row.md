---
title: A Carry on row in Library
kind: feature
status: built 2026-11-06, Batch A (tests written 2026-11-07)
area: library
updated: 2026-11-07
---

# A Carry on row in Library

Dewi, 2026-11-06. [ADR 137](../adr/0137-carry-on-is-play-history-joined-to-progress.md).

## What it does

The top of the Library tab (under its header, above the group tiles) has a **▶️ Carry on** row: up to 12 things you
started and did not finish, most recently played first. Each card is the picture with its progress bar and length,
the title, and how far in you are ("▶️ 34 of 96 minutes", or "▶️ 12 minutes in" while the length is unknown). Tap to
carry on from where you were (it joins the queue after what is playing, like the History page's tap); long-press for
the same menu as any row.

It is hidden while there is nothing to carry on.

## What counts

| Rule | Reason |
|---|---|
| In the play history, most recent first | the history is the only store with titles and pictures |
| `MediaFilter.IN_PROGRESS` accepts its state (the account-merged map every list reads) | one definition of "part-way" |
| Not nearly finished (`isNearlyFinished`) | the app's one "done" rule |
| At least a minute in (`CARRY_ON_MIN_MS`, 2026-11-07) | "0 of 62 minutes" rows were items never really started; Carry on is what you started and did not finish |
| Not music, not live | nothing to resume |
| Not 🙅 Not for me | a row that suggests follows the suppression list |

## Seams and reuse

`carryOn` in `:core:domain`; `CarryOnViewModel` (history without the not-for-me items, and the tap) and the cards in
`ui/library/CarryOn.kt`; the hub takes a `LibraryCarryOn` like `LibraryGroups`. Reused: `MediaFilter.IN_PROGRESS`,
`isNearlyFinished`, `PlayProgressSliver`, `MediaThumbnail`, `ItemActionSheet`, `PlaybackQueue.playAll`,
`LocalPlayStates`, `NotForMe.without`, the `episodes_state_started` plural.

## Diagnostics

`library` logs which items the row shows, with their positions, from how many played items; `carry-on` logs a tap or
long-press with the item's id and position.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`CarryOnTest` (`:core:domain`, 10: history order, unplayed and played and unknown left out, the finished tail by the
state's length and by a short item's last tenth, the item's own length when the state has none, no length anywhere,
music and live left out, the 12 cap, the state carried for the bar), `CarryOnViewModelTest` (the history without
not-for-me items and following the list, a tap queueing a run of one), instrumented `BatchAScreensTest` (the row's
title, each card's words and tap, absent when empty). Seen failing with the live exclusion removed.

Still owed: instrumented, the progress bar and the long-press item menu.
