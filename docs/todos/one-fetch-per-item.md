---
title: One fetch per item, the stream is the download
kind: todo
status: built 2026-11-20 (ADR 186; instrumented evidence on totum-api35, not yet seen on the phone)
area: downloads/playback
priority: high
updated: 2026-11-20
---

# One fetch per item, the stream is the download

Report `20261120T200136-b1afd577`: the queue auto-download fetched the playing item's audio beside its own stream, and the
finished file replaced a healthy stream, stopping the sound for 1.3 s. Dewi: *"click an item, it streams to my ears AND to a
file"*, one fetch, by default. Decision, design and measurements: [ADR 186](../adr/0186-one-fetch-per-item-a-playing-stream-is-its-own-download.md).

## Built

- `DeferredHandOver`: a finished download swaps in only at a pause, a stall or a seek.
- `QueueAutoDownloader.heldWhileStreaming`: a streaming item that cannot be teed is fetched at a pause or stop.
- `PlaybackTee` + `StreamTeePlan` + `StreamTeeFiller` + `TeeDownloadStrategy`: a playing YouTube audio stream's download is
  made from the cache's bytes, fetching only what the player did not.
- Network bytes counted per stream; the completion line gives the multiple of the file.

## Open

- Whether the tee's late chunks keep working against YouTube's real, expiring address (the fall-back covers a refusal): read
  the `tee` lines of the next reports. Expected: `1.00x the file` on every teed download.
- A manual **video** download of the playing item still fetches its own bytes (a different format; logged, not held back).
- Podcast enclosures and SABR/live streams are not teed (per-launch cache key, no length, or bypassing the cache); they only
  avoid the double fetch by waiting for a pause or stop.
- The tee's download reads as downloading until the player has loaded the whole file; the auto-downloader's lane stops waiting
  for it after 10 minutes (it logs that) and the download carries on.
