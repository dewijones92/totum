---
title: The queue tidies itself
kind: feature
status: built 2026-11-06 (tests written 2026-11-07)
area: queue
updated: 2026-11-07
---

# The queue tidies itself

Dewi, 2026-11-06: queues of 5,828 and 11,460 items. Weekly (`queue-tidy`, a [periodic job](periodic-jobs.md)), played
talk older than the chosen age leaves the queue, and failed downloads are cleared.

## Rules (`QueueTidy`, `app/.../queue/QueueTidy.kt`)

An entry leaves only when **all** hold: it is talk (never `Listening.MUSIC`), it is not 🔒 locked, it is not the
playing item or in the playing item's group, it is played here (`PlayState.Played` in the local progress store), and
its last saved position is at least the age old. A finished item with no saved date is kept (never guessed old). Items in
progress, unplayed items and music are never touched. The queue is waited for (up to 30 s) to finish loading from disk
first, so the job cannot run against an empty queue.

Failed downloads go through `DownloadManager.clearFailed(why)` (`core/data/.../ClearFailed.kt`), which the Downloads
page's "Clear all" now calls too, so there is one copy of the rule.

## What the person sees

- **Settings → Queue:** "Tidy the queue each week" (on by default) and "Tidy played talk older than" 7 / 14 / 30 /
  60 days (14 by default), with the last run under the switch: "✅ Queue tidied 2 days ago: 12 played items left the
  queue, 3 failed downloads cleared" (or "nothing to tidy", or "❌ The last tidy-up failed: …"). Both rows are in the
  Settings registry (`TIDY_QUEUE`, `TIDY_AGE`).
- **A notification** ("🧹 Queue tidied", housekeeping channel) when something moved, with **↩️ Undo** (2026-11-06).
  The tidied entries and the slots they left are kept in `queue-tidy-undo.json` (`TidyUndoStore`, entries as
  `SpeakerEntry`, the app's existing serialisable queue entry) for 6 hours, because the weekly worker's process is usually
  gone by the time you tap. Undo (`QueueTidyUndoReceiver` → `QueueTidy.undo`, waits for the queue to load) puts each
  entry back at its old slot with `PlaybackQueue.restoreAt`, cancels the notice and says how many came back; swiping the
  notice away, or the six hours passing, forgets the batch. Failed downloads that were cleared are not brought back (they were
  only records of failures).
- Items that "leave the queue for History": played items are recorded in History when they are played, so nothing is
  re-recorded here (that would reorder History by the tidy time). History keeps the latest 50 plays, so most items this
  old are already past it.

## Diagnostics

`tidy` lines: one summary per run (queued, finished, leaving, too recent, undated, and the kept-because counts:
music, locked, playing now, playing group, not finished), up to 50 leaving titles, and the result; `download` line
`clear failed (queue tidy-up, weekly): N failed download(s) forgotten`; the `jobs` lines.

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
