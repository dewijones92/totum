---
title: Downloads clean-up suggestions
kind: feature
status: built 2026-10-13 — not yet on the phone
area: downloads
updated: 2026-10-13
---

# Downloads clean-up suggestions

Under the storage line in Library's Downloads, **Suggested clean-up** shows a card per group that has
anything in it: `🧹 Free 2.3 GB · 14 not opened in 30 days`, then finished, over 500 MB, and not in the
queue. A card opens the list with every file ticked; untick what to keep and press `Delete N · X`.
Deleted items leave the queue too. Both pillars, one rule. Decision and reasons:
[ADR 31](../adr/0031-downloads-suggest-groups-to-delete.md).

## Seam

- `core/domain/…/DownloadCleanup.kt`: `suggestCleanup` (the rule), `CleanupCandidate`, `CleanupRules`
  (30 days, 500 MB).
- Dates on the download row (`DownloadEntity.downloadedAtEpochMs`, `openedAtEpochMs`, database v27),
  surfaced on `DownloadedMedia`; `DownloadManager.noteOpened`, called from the controller's `onPlay` in
  `AppContainer`.
- `app/…/ui/library/LibraryViewModel.kt`: `cleanup` (downloads × play states × queue) and
  `deleteSuggested`, which takes each matching entry out with `PlaybackQueue.remove`.
- `app/…/ui/library/CleanupCards.kt`: the cards and the review dialog, placed by `LibraryContent`.

## Logs

`[library] clean-up suggestions from N download(s): NOT_OPENED=3/1.2 GB, …` whenever the groups change;
`clean-up BIG opened`, `… ticked, … kept`, and `clean-up BIG: deleting N file(s), X, M taken out of the
queue: <id> (size, downloaded=…, opened=…)`. `[download] opened <id>` when a play starts on a downloaded
item.

## Tests

- `DownloadCleanupTest` (JVM): each group, the 30-day and 500 MB boundaries, a re-download judged from
  its new date, undated files never unopened, a file in several groups, largest first, nothing → nothing.
- `CleanupSuggestionsTest` (JVM): groups from files, play states and queue together; delete removes the
  files and their queue entries; unticked files untouched.
- `CleanupCardsTest` (instrumented): card text, review starts all ticked, untick then delete, nothing
  ticked cannot delete, no suggestions no cards.
- `DownloadDatesTest` (instrumented, `:core:database`): dated on finishing, kept on rewrite with its open,
  a re-download re-dated, an open of nothing is a no-op, the v26→v27 migration dates existing rows.
