---
title: Shared links recover their titles after an offline arrival
kind: feature
status: shipped
area: queue/downloads
updated: 2026-11-14
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

Repair at a big queue's scale (2026-11-14, report 20261114T050301, v0.1.918: 5,857 queue rows, 114 torrent rows
from 8 torrents). The application scope is the main thread and a lookup's reply was read on it, so every poster lookup
threw `NetworkOnMainThreadException`; every lookup now runs on `Dispatchers.IO` (`off`), and only the adoption and the
download write come back to the caller. A pass asks for a poster once per torrent name (30 files of one torrent are
one lookup, a failure is shared too), works in batches of 20 rows with a second between batches, and checks the
network before each batch. The `[metadata]` trail is one `pass:` line per pass (rows due of the candidates,
repaired/none/failed per need, poster lookups and reuses, gave up and retrying, failure reasons by class name,
batches, time, a few examples) and one warning with the stack for the first failure of the pass, never a line per
row. Coverage: `SharedMetadataRepairTest` on the JVM; `RoomDownloadStoreTest` for actual persistence,
preserving facts across an older download write and refusing to recreate deleted rows.
