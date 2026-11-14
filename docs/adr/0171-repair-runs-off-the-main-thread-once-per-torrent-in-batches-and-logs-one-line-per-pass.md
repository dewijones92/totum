---
title: Metadata repair runs off the main thread, asks once per torrent, works in batches, and both it and the spoiler shield log one line per pass
kind: adr
status: accepted
updated: 2026-11-14
---

# 171. Metadata repair runs off the main thread, asks once per torrent, works in batches, and both it and the spoiler shield log one line per pass

- Status: Accepted
- Date: 2026-11-14
- Amends: [ADR 141](0141-a-spoiler-shield-hides-results-in-titles-pictures-and-lengths.md) (what the shield logs),
  [ADR 168](0168-home-torrents-hold-together-retries-shown-main-file-first-and-a-fixed-remux.md) (the poster lookup
  `SharedMetadataRepair` runs)

## Context

Diagnostics 20261114T050301 from Dewi's phone (v0.1.918, 5,857 queue rows) held 1,400 events:

- 989 were `spoiler` lines, one per row, 542 of them in a single second at start-up. `spoiler` is a kept tag (a
  1,000-line deque shared with every other kept tag), so they also pushed out every other kept tag's history.
- 228 were `metadata` lines: 114 torrent rows from 8 torrents each logged `metadata repair failed —
  NetworkOnMainThreadException` and then "no poster after 2 tries; not asking YouTube again". The repair runs in the
  application scope, which is the main thread; `Call.await()` resumes the caller on its own dispatcher, so the
  catalogue's reply was read on the main thread. The row-by-row repair also asked the catalogue once per file, not once
  per torrent.

## Decision

- Every lookup `SharedMetadataRepair` makes (title, date, artist, poster) runs on `Dispatchers.IO`; only the queue
  adoption and the download write come back to the caller. `Cinemeta.get` moves its own request and body read onto IO
  too, so any other caller of the poster lookup is safe without the repair's help.
- A pass asks for a poster once per torrent name (a miss or a failure is shared by the torrent's other files), works
  in batches of 20 rows with a second between them, and checks the network before each batch.
- A pass is one `pass:` line (rows due of the candidates, repaired/none/failed per need, poster lookups and reuses,
  gave up and retrying, failure reasons by class, batches, time, three examples) plus one warning with the stack for
  the first failure. The spoiler shield counts the rows it shows into one `faces:` line per 2 s window, every answer
  into one `verdicts:` line, and keeps a line of its own only where the rules overrode the model.

## Consequences

- A start-up pass over Dewi's queue is 8 poster lookups and about two lines instead of 228 lookups' worth of lines;
  the shield's 989 lines are one.
- A genuinely interesting spoiler case (a rewrite the rules rejected) is still visible per title.
- A pass's examples name rows but not every row, so "which row failed" is answered by the reason counts and the first
  stack, not a list. A row that matters is found by its own later pass or the queue state block.
