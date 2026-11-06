---
title: Already queued? Say so
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Already queued? Say so

Dewi 2026-11-06: adding something already queued says "already queued at #N, move it up?". Today `enqueue`/`playNext`
move it silently and `addToGroup` skips silently (`QueueSnapshotOps.relocating`); the prompt replaces both.

## Built 2026-11-06

The rule is on the queue's entry seam (`addingUnlessQueued`), the prompt is one collector (`QueueNoticeToasts`); see
[the queue doc](../features/playback-queue.md). `PlaybackQueueTest` and `QueueBulkEditsTest` were updated: the old
"moves an already-queued item" assertions are now "leaves it and says so", and the bulk-equals-single property compares
against an explicit move.

Tests to add: the three `AlreadyQueued` shapes (later, next, playing) and that nothing moves; `moveUp` keeps the group and
does nothing for the playing entry; `SomeAlreadyQueued` once per bulk call, never for `byYou = false`; the shell collector
(snackbar with Move up, toast otherwise).
