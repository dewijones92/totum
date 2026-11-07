---
title: The queue tidies itself
kind: todo
status: built 2026-11-06 (tests written 2026-11-07)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# The queue tidies itself

Queues of 5,828 (Pixel 7) and 11,460 (Pixel 4a) items. Proposed default (confirm at build): weekly, played talk
older than 14 days leaves the queue for History (never music, never 🔒 or the playing group), failed downloads cleared;
a line says what moved, with Undo.

Whole-batch review: clearing failed downloads already exists (`LibraryViewModel.clearFailed`); call it. The weekly run uses the shared periodic job.

Built: see [../features/queue-tidy-up.md](../features/queue-tidy-up.md).

## Tests (Batch A testing stretch, part A, 2026-11-07)

`QueueTidyTest` (13: age boundary exactly at the cut-off and a moment newer, undated kept, music, locked, in progress,
unplayed, the playing item and its group kept, failed downloads cleared and counted, undo into the old slots, undo twice,
an item queued again since, a run that tidies nothing keeping an earlier batch, forgetting the undo, every
`tidyKeepReason` and their order), `TidyUndoStoreTest` (8: taken once, the window boundary, a late take forgetting the
batch, a newer batch replacing, the file round trip, a dismissed batch, an unreadable file), and the notice's words in
`BatchAWordsTest` (instrumented). Seen failing with the cut-off comparison, the canonical window and the undo window
each changed.

Still owed: the worker (`QueueTidyWorker`, WorkManager) with the setting off and with an unloaded queue,
`QueueTidyUndoReceiver`, the Settings rows.
