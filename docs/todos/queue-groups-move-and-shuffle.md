---
title: Queue groups move and shuffle like items
kind: todo
status: built 2026-10-14 → ADR 40, docs/features/playback-queue.md
area: queue
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# Move and shuffle queue groups

Dewi, 2026-10-14: *"there are groups within the queue? i want to be able to shuffle them and move them (just
like any other queue item)"*.

## Agreed

- **Drag a whole group** by its header's handle; its items move together, in order.
- The group header's menu gains **Move to top**, **Move to bottom** and **Play next**.
- **Shuffle inside a group** from its header; and a way to **shuffle the order of the groups** themselves.
- Both pillars; one queue operation per action, logged with the group, its size and where it went.

Also in the same change (Dewi, 2026-10-14: *"make the drag (in queue page) far more usable - maybe rewrite it"*):
the whole queue drag moved to the Reorderable library; see ADR 40.
