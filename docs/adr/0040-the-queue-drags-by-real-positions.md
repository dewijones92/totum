---
title: The queue drags with the Reorderable library, and a group moves as one block
kind: adr
status: accepted
updated: 2026-10-14
---

# 40. The queue drags with the Reorderable library, and a group moves as one block

- Status: Accepted; replaces the hand-rolled `ReorderState` (no ADR recorded it; its KDoc said "hand-rolled
  rather than pulling in a dependency")
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"make the drag (in queue page) far more usable - maybe rewrite it … when I drag it and my
fingers travel down the page while dragging - the item seems to jump"*, and in the same batch: *"i want to be
able to shuffle them and move them (just like any other queue item)"* about queue groups.

`ReorderState` swapped a row each time the accumulated drag passed the height of the row being crossed. Rows
the queue interleaves (group headers, the label over the playing row) were not in that sum, so the row
drifted from the finger every time a drag crossed one. A test that measures the dragged row against the
finger frame by frame (`QueueRowDragTest.theDraggedRowStaysUnderTheFingerAcrossGroupHeaders`) gives the
numbers on both versions; see Consequences.

## Decision

- **Drag with `sh.calvin.reorderable` 3.1.0** (Apache 2.0; 1.4k stars; used by Pocket Casts, Home Assistant,
  Mihon and InnerTune; one maintainer, a release every 6 to 8 months). Dewi's choice over a hand-rolled
  rewrite, on the condition that it is well supported. It moves rows by their real positions in the lazy
  list, animates the others out of the way and scrolls at the edges.
- **One drag for rows and groups.** Every row and every group's first header is a `ReorderableItem`;
  `dragMove` in `queue/QueueDrag.kt` (pure, unit-tested) turns the two lazy keys into a queue operation:
  row over row is `move`, a header over anything is `moveBlock`. A row is never dropped on a header (headers
  are drop targets only while a group is being dragged).
- **A group is a block**: all its entries, gathered in order, even when the group had been split. Dragging a
  header folds the group first, so the block is one row high under the finger.
- **A split group's second header is hidden while a drag is in flight.** Dragging a row into the middle of a
  group splits it, and the new header appearing under the drag shifted the layout by its own height (58dp on
  the emulator): exactly the jump Dewi saw. It reappears on release.
- The group header's ⋮ gains **Play next, Move to top, Move to bottom, Shuffle this group** (beside Remove);
  the Queue header's 🔀 offers **Shuffle up next** and **Shuffle the order of groups** when up next has a group.
  Shuffling never touches what has played or what is playing. All pure functions in `queue/QueueGroupMoves.kt`.

## Consequences

- `ReorderState`, `ReorderStateTest` and `ReorderAutoScrollTest` are deleted; `QueueRowDragTest` covers the
  real screen: about two places for two rows of travel, the row staying within 24dp of the finger across group
  headers, a header dragging its whole group, and a drag held at the bottom carrying a row past a screenful.
- Measured by the same test, 8.5dp per frame, four rows past two groups: the **old drag jumped 88–90dp off the
  finger** crossing the first group and 50dp crossing the second (red); the new one stays 8dp behind (the touch
  slop before a drag starts) and 18–23dp after crossing a group (green), also at 2dp per frame.
- A dependency now owns the gesture. If it stalls, `dragMove` and the group moves are ours and library-free;
  only `ui/queue/QueueDrag.kt` and the `ReorderableItem` calls in `QueueScreen` would change.
- Logged: `drag picked up item|group <key>`, each move as `move a->b` / `move block a->b`, `drag released
  <key> after N move(s)`, and any ignored target with its reason.

## Amended 2026-10-14, after the batch review

- **A dragged group moves past whole runs, and only it is gathered.** The first version treated every group as one
  block and rebuilt the whole queue, so dragging one group pulled every other split group back together: a "Play
  next" item that had split the playing album was carried off to after it. Now `movingGroupPast` takes the
  dragged group's entries, steps past the consecutive run the finger is over (after it going down, before it going
  up), and leaves every other entry where it was. `QueueGroupMovesTest` has the reviewer's case (album split around
  the playing item by a "Play next" episode), red on the old code.
- A group folded by its own drag unfolds again when it is dropped.
