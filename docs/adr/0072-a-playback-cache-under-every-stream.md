---
title: A playback cache under every stream, so going back plays from the disk
kind: adr
status: accepted
updated: 2026-10-22
---

# 72. A playback cache under every stream

- Status: Accepted
- Date: 2026-10-22

## Context

Dewi, 2026-10-22: rewinding a streamed torrent re-buffered, and he wanted the same for everything: *"I don't mean
that specifically for torrents. I mean that for anything, video, podcast, torrents"*. The player keeps only what is
ahead of the playhead, so any step back went to the network again.

## Decision

- One process-wide Media3 `SimpleCache` in the app's cache folder, 1 GB, least recently used evicted
  (`PlaybackCache`). It sits under the ranged fetcher (`ChunkedDataSource`), so cached bytes and fetched bytes go
  through the same path, and above SABR's own source, which it never sees.
- A router sends a request through the cache only when `PlaybackCacheKeys.cacheable` says so: http(s), not loopback,
  not a manifest, not a live segment (`sq=`, `live=1`). SABR, local files and live go direct.
- Keys: a YouTube stream is keyed by its stream id, itag and length, not its URL, because every lookup signs a new
  URL for the same bytes; a torrent stream drops its rotating `totumToken`; anything else is its URL.
- A read error bypasses the cache for that request rather than failing playback.
- The seek bar shows the buffer ahead (pale tangerine, Dewi's choice) and what has played this session, which the
  cache holds, in cyan (`PlayedSpans`).

## Consequences

- Going back over anything played recently is read from the disk (emulator: 28 MB from disk on a rewind into
  earlier-played ground, 62 MB written in a minute). A second play of a video's opening also comes from the disk.
- Up to 1 GB of the phone's cache space. Android may clear it when storage runs low, which only costs a re-fetch.
- The cache is keyed so it can serve bytes from a lookup that has since expired; that is what makes it useful, and
  YouTube sees fewer requests.
- "Cyan behind" is played-this-session, an approximation of what the cache holds: it does not show bytes cached by an
  earlier session, and it would overstate after an eviction (1 GB is many hours of audio).
