---
title: Suggest groups of downloads to delete
kind: todo
status: built 2026-10-13 — see features/downloads-cleanup.md and ADR 31
area: downloads
priority: high
requested: 2026-10-13
updated: 2026-10-13
---

# Suggest groups of downloads to delete

Dewi, 2026-10-13: *"in the downloads section of the app [I want it] to suggest group to batch delete
… i.e. big files or files that I havent opened in over 30 days etc"*. Top of the backlog at his
request.

## Agreed (Dewi, 2026-10-13)

- **Four suggested groups**, shown as cards at the top of Downloads, each with its count and the
  space it would free (`🧹 Free 2.3 GB · 14 not opened in 30 days`):
  1. **Not opened in 30+ days**: counted from the last play, or from the download if never played.
  2. **Finished**: watched or listened to the end.
  3. **Big files**: any single file over 500 MB.
  4. **Not in the queue any more**: on disk but not in the current queue.
  A file may sit in more than one group.
- **Review, then delete.** A card opens its list with every file ticked; untick what to keep, then
  `Delete N · X GB`. Never one-tap.
- **Nothing is protected**: queued and pinned items are suggested like anything else.
- **Deleting a queued item's file also removes it from the queue**, so queue auto-download does not
  fetch it straight back.
- Both pillars, one rule (a podcast episode and a video are judged the same way).

## What exists (read from the code, `main` at `4c612353`)

- **Downloads list**: `ui/library/LibraryScreen.kt` (rows show size on disk), in the shared
  multi-select list (`ui/common/Selection.kt`, `SelectableMediaList`), whose bulk "Delete downloaded
  file" has no confirm step. No delete-all. `StorageSummary` already shows used and free space.
- **Size**: not stored for a finished download; read as `File(localPath).length()`.
- **No download date** is stored (`DownloadEntity` in `core/database/…/PodcastEntities.kt`).
  The file's own modification time is the likely stand-in: not yet checked that it survives.
- **Last opened**: `playback_progress.updatedAtEpochMs` (changes on every save) and
  `completedAtEpochMs` (finished). Positions under 5 s are not saved, so a file opened for a moment
  reads as never played. `play_history` keeps only 50 rows, so it cannot answer "30 days".
- **No automatic clean-up** of any kind today.

## Design notes for whoever builds it

- The pillar-agnostic seam: a pure rule in `:core:domain`/`:core:data` that takes the downloaded
  items, their progress, the queue's ids, sizes and "now", and returns the groups. Unit-test it
  there (boundaries: exactly 30 days, exactly 500 MB, never played, finished and old in two groups).
- Log each suggestion: group, count, bytes, the inputs per item (last opened, finished, size, queued);
  and each delete: what was asked, what was deleted, freed bytes, which queue entries went.
- Decide whether a missing download date means a new column (migration) or the file's mtime.
- ADR: suggestions never delete on their own; deleting from a suggestion takes queued items out of
  the queue.

## Open

- Nothing. File mtime was rejected as the download date: yt-dlp's default stamps the server's date
  (ADR 31), so the row records its own dates instead.
