---
title: Trip mode
kind: todo
status: built 2026-11-07, Batch A (tests owed; not run on a device)
batch: A (2026-11)
priority: low
updated: 2026-11-07
---

# Trip mode

Dewi 2026-11-05 (agreed idea): "I'm leaving in 2 hours" fills the phone over Wi-Fi with the next few of each queue group plus whatever the Pi has ready, shown in the progress strip. Reuse the queue auto-download, Keep the next N at home and the Pi copy (now off by default).

Whole-batch review: Pi files reach the phone only through "📥 Copy to the phone now" (automatic copying is switched off by pi-files-never-copied-by-playing); never a fourth download path.

## Built 2026-11-07 ([ADR 149](../adr/0149-trip-mode-fills-the-phone-through-the-existing-download-paths.md))

A Library tile and page: choose when you leave and how many per group, and the phone fetches the next few of every group
over Wi-Fi as one progress-strip task, and copies what the Pi has ready through "📥 Copy to the phone now" only. Reuses
`aheadFor`, `EpisodeDownloads.fetchBatch`, `HomeDownloads.copyNow` and a `PeriodicJob`; no new download path. See
[trip-mode](../features/trip-mode.md). Not run on a device.

## Tests owed

A Compose test of the tile and page; a live run on a phone and a Pi; no departure notification yet.
