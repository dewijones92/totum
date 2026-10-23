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
  (`PlaybackCache`). It sits above the ranged fetcher (`ChunkedDataSource`), which fetches whatever it does not hold,
  and below SABR's own source, which it never sees; cached bytes are read from the disk by Media3, not through the
  fetcher. (Corrected 2026-10-22: this said "under", which was backwards.)
- A router sends a request through the cache only when `PlaybackCacheKeys.cacheable` says so: http(s), not loopback,
  not a manifest, not a live segment (`sq=`, `live=1`). SABR, local files and live go direct.
- Keys: a YouTube stream is keyed by its stream id, itag, length and `xtags` (a dubbed track shares id and itag), not
  its URL, because every lookup signs a new URL for the same bytes; a torrent stream drops its rotating `totumToken`;
  anything else, and a YouTube URL with no length, is its URL **for this launch only**: a podcast enclosure URL can
  serve a different file tomorrow (dynamic ad insertion, a corrected upload), and mixing two versions' bytes would play
  the wrong audio at the joins (review, 2026-10-22).
- A read error from the cache fails that read; the load is retried and that data source skips the cache for the rest
  of the item. A stream of unknown length is not stored (Media3's `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` on progressive
  loads), so it is not quite "everything". (Corrected 2026-10-22.)
- Every open says what it decided (through the cache with how much is held from that position, or direct and why),
  at most once per stream every 5 s; a report carries `cache.heldMb`, `cache.readFromDiskMb`, `cache.opens` and
  `cache.ignoredAfterErrorOrUnknownLength`. The size is read off the main thread (opening the cache had blocked the
  service's start on a 1 GB folder scan).
- The seek bar shows the buffer ahead (pale tangerine, Dewi's choice) and what has played this session, which the
  cache holds, in cyan (`PlayedSpans`).

## Consequences

- **Side-loaded subtitles are kept although their length is unknown** (2026-10-23). Media3 loads them with
  `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN`, and YouTube's timedtext answers carry no length, so they were never stored
  and every seek fetched them again (256 KB in 0.7 s on Dewi's phone, 3.5 s on the emulator) while playback
  waited. Every subtitle address goes through `captionConfiguration`, which registers it (`SideLoadedText`), and
  the cache's router drops that flag for registered addresses only. Media still follows the flag.

- Going back over anything played recently is read from the disk (emulator: 28 MB from disk on a rewind into
  earlier-played ground, 62 MB written in a minute). A second play of a video's opening also comes from the disk.
- Up to 1 GB of the phone's cache space. Android may clear it when storage runs low, which only costs a re-fetch.
- The cache is keyed so it can serve bytes from a lookup that has since expired; that is what makes it useful, and
  YouTube sees fewer requests.
- "Cyan behind" is played-this-session, an approximation of what the cache holds: it does not show bytes cached by an
  earlier session, and it would overstate after an eviction (1 GB is many hours of audio).
