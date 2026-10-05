---
title: Shared links recover their titles after an offline arrival
kind: feature
status: shipped
area: queue/downloads
updated: 2026-10-05
---

# Shared-link metadata

Report `20261005T062054-903c8da3`, v0.1.558 / `73b5db8`, Dewi: "title not downloaded???".
The share for `4kKd9-vCPts` arrived during a DNS failure, so its title was the placeholder
`YouTube video 4kKd9-vCPts`. The audio downloaded successfully at 07:16:50; subsequent plays
routed to that local copy and never resolved metadata, leaving the placeholder in both stores.

`SharedMetadataRepair` runs in the application scope. It observes placeholder entries from both
the queue and download records, waits for a validated network, and describes them through the
existing `VideoPlaybackLauncher`/`VideoResolver`. It never holds up playback of a local file.
Failed lookups retry after 30 seconds without needing a queue edit. Changes to the candidate list
cancel obsolete work; an item no longer held in either store is not repaired.

The queue and download record fill only missing facts through `fillingSilenceFrom`. File paths,
audio-only flags, handles and download states survive. Repairing an already-downloaded item does
not download its bytes again. Existing placeholder downloads are candidates on the next launch.

The `[metadata]` trail says when a title remains unavailable and when both stores learn it.
Coverage: `SharedMetadataRepairTest` on the JVM; `RoomDownloadStoreTest` for actual persistence,
preserving facts across an older download write and refusing to recreate deleted rows.
