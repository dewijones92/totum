---
title: A group page shows its last list while refreshing
kind: feature
status: built 2026-11-06
area: video
updated: 2026-11-06
---

# A group page shows its last list while refreshing

Report 20261103T193146-627bd592: a group page was blank for about 13 s, because `GroupFeed` kept each group's last
list in memory only, so every launch started empty.

- `GroupFeed` now persists the kept lists to `group-feed.json` in the app's files directory, written with
  `File.writeTextReplacing` and reloaded when it is constructed. Items are stored as `BackupItem`s, the same shape the
  queue and downloads use, so a restored row reads like any other.
- The page shows the kept list at once, filtered by the group's current members (`lastKnown`), under a
  `⏳ Refreshing… showing the last list meanwhile` line while the refresh runs; the live progress strip still reports
  the refresh as before.
- A source that cannot be read keeps its items from the last list rather than being treated as an empty channel, so an
  offline refresh no longer wipes what the page was showing (and what is now on disk). The log says how many sources
  could not be read and how many items stayed.
- `GroupNewsWiring`'s store moved off its private temp-file-and-rename copy onto `writeTextReplacing`.
- Diagnostics: `dewidebug loaded the kept lists …` at start, `dewidebug kept N group list(s) …` after each save, and
  warnings (never silent) when the file cannot be read or written.

Not done: a deleted group's list stays in the file (it is never shown); the file is not in the Drive backup, being a
cache.

## Tests still to write

`GroupFeed` with a file: a list survives a new instance; a member removed from the group is not shown from the kept
list; a corrupt file starts empty and is logged; a failed member's items are carried over and a member that returns
nothing is not; two groups finishing together leave both in the file. `FileGroupNewsStore` round trip. A device check
that a cold start shows the list at once.

## Tests owed (2026-11-06 consistency sweep)

Unticking a channel in the edit sheet offers Undo on the sheet's own snackbar and ticks it again (instrumented).
