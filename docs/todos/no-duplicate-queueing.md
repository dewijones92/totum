---
title: Already queued? Say so
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07: AlreadyQueuedNoticesTest; the shell collector QueueNoticeToasts is not covered; not seen on a device)
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# Already queued? Say so

Dewi 2026-11-06: adding something already queued says "already queued at #N, move it up?". Today `enqueue`/`playNext`
move it silently and `addToGroup` skips silently (`QueueSnapshotOps.relocating`); the prompt replaces both.

## Built 2026-11-06

The rule is on the queue's entry seam (`addingUnlessQueued`), the prompt is one collector (`QueueNoticeToasts`); see
[the queue doc](../features/playback-queue.md). `PlaybackQueueTest` and `QueueBulkEditsTest` were updated: the old
"moves an already-queued item" assertions are now "leaves it and says so", and the bulk-equals-single property compares
against an explicit move.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`AlreadyQueuedNoticesTest` (9): a new add says nothing; later in the queue (`position`, `canMoveUp`), next (`next`, no
move up) and playing (`playing`) each say so and move nothing; `moveUp` keeps the group and does nothing for the playing
entry; `SomeAlreadyQueued` once per bulk call and counted once for a repeat inside the list; `addToGroup` and
`playAll` say so for `byYou = true` and stay silent for `byYou = false`, and `playAll` never counts the playing item.
The wording of each notice: `BatchAWordsTest` (instrumented). Seen failing with the position shifted by one.

Still owed: the shell collector `QueueNoticeToasts` (a snackbar with Move up, a toast otherwise).
