---
title: The queue tidies itself
kind: todo
status: built 2026-11-06 (tests to follow)
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# The queue tidies itself

Queues of 5,828 (Pixel 7) and 11,460 (Pixel 4a) items. Proposed default (confirm at build): weekly, played talk
older than 14 days leaves the queue for History (never music, never 🔒 or the playing group), failed downloads cleared;
a line says what moved, with Undo.

Whole-batch review: clearing failed downloads already exists (`LibraryViewModel.clearFailed`); call it. The weekly run uses the shared periodic job.

Built: see [../features/queue-tidy-up.md](../features/queue-tidy-up.md).
