---
title: Reset a whole group or list to the start
kind: todo
status: built; tests written in part
area: queue/library
priority: medium
requested: 2026-10-22
updated: 2026-11-14
---

# Reset a whole group or list to the start

One action that marks every item of a queue group or list unplayed (each to 0). Per item "Mark as unplayed" exists.

## Tests written 2026-11-14

`QueueGroupIdsTest` (the ids "Start again" walks: a group's members in queue order, nothing else, following a reorder, 300 among 5,800). `RoomPlaybackProgressStoreTest` already covers `setPlayed(false)` deleting the position. Owed: the snackbar and the menu item (`startAgain` is composable).
