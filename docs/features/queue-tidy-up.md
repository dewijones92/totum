---
title: The queue tidies itself
kind: feature
status: built 2026-11-06 (tests to follow)
area: queue
updated: 2026-11-06
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
- **A notification** ("🧹 Queue tidied", housekeeping channel) when something moved. The shared Undo had not landed on
  `main` when this was built, so there is no Undo; the entries leave as a plain notice. When it lands, the notice
  should offer it.
- Items that "leave the queue for History": played items are recorded in History when they are played, so nothing is
  re-recorded here (that would reorder History by the tidy time). History keeps the latest 50 plays, so most items this
  old are already past it.

## Diagnostics

`tidy` lines: one summary per run (queued, finished, leaving, too recent, undated, and the kept-because counts:
music, locked, playing now, playing group, not finished), up to 50 leaving titles, and the result; `download` line
`clear failed (queue tidy-up, weekly): N failed download(s) forgotten`; the `jobs` lines.

## Tests still to write

`tidyKeepReason` per rule; `QueueTidy.tidy` against a fake queue and progress store (age boundary, undated, locked,
playing group, music, in progress); the worker with the setting off and with an unloaded queue; `clearFailed` against
`FakeDownloadManager`; Settings rows.
