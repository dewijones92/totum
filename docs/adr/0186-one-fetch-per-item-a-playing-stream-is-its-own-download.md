---
title: One fetch per item, a playing stream is its own download, and nothing swaps a healthy stream for a file
kind: adr
status: accepted
updated: 2026-11-20
---

# 186. One fetch per item: a playing stream is its own download, and nothing swaps a healthy stream for a file

- Status: Accepted
- Date: 2026-11-20

## Context

Report `20261120T200136-b1afd577` (0.1.938), Dewi: *"why was I buffering a few seconds into the video? it played like
10 seconds and it buffered"*. The trail:

- `20:01:09.657 wf8YXNWhkvc finished downloading while streaming: switching to the downloaded audio … at 7904ms`, then
  `wants to play during its hand over to the download; the rescue will play it`, then
  `20:01:10.995 playback ready after 1296ms at 7913ms`. The same at 19:58:26 for `-pfEWYjniMM` (1286 ms).
- The stream was healthy (about 78 Mbps). The queue auto-download had fetched the same audio (3.6 MB, yt-dlp) beside the
  player's own fetch of it, and the finished file then replaced a stream that needed no replacing ([ADR 13](0013-playback-start-young-urls-held-bytes-and-copies.md) point 4).

Two defects, one cause. The same bytes crossed the network twice, and the second copy was swapped in mid-play at the cost
of a player rebuild. Dewi's rule, in his words: *"click an item, it streams to my ears AND to a file"*: one fetch, and
that is the default, because downloading the audio of the queue is already the app's default.

## Decision

1. **A healthy stream is never swapped for a file.** When the download of the playing item lands while it streams
   steadily (`wantsToPlay`, playing, not buffering), `PlaybackQueue.handOverToTheDownload` defers the switch and logs why
   (position, buffered-ahead). It then swaps at the first natural break, judged by `DeferredHandOver` on the controller's
   state: a **pause**, the stream **stalling** (buffering), or a **jump** of the position of 3 s or more (a seek, a skip).
   Starting the item again, another item playing, or the item ending drops the deferral (a start takes the copy through
   `routeNow` by itself). The swap logs its trigger and how far the position had moved since the download finished.
2. **While an item streams, nothing else fetches the same media.** `QueueAutoDownloader` takes `heldWhileStreaming`: the
   item that is playing from a stream whose bytes cannot become its copy. That item is left alone (one log line:
   *not fetching … while it streams … fetched once it pauses or stops, if still queued*) and is fetched when it pauses, ends or
   another item takes over, if it is still in the queue and not downloaded. This reverses the "playing item first" ordering
   of report 0.1.435 for a streaming item with no tee; once it is not streaming, it is first again.
3. **The stream is its own download (the tee).** Media3's playback cache ([ADR 72](0072-a-playback-cache-under-every-stream.md))
   already writes whatever the player reads. For a YouTube **audio** stream read through it, `PlaybackTee` remembers the
   stream (cache key `yt:<id>:<itag>:<clen>:<xtags>`, its latest signed address, its length) and
   `TeeDownloadStrategy` (wrapping the app's download strategy) makes the audio download **from those bytes**:
   - `StreamTeeFiller` fetches only the byte ranges the cache does not hold, through the same cache key, in 256 KB chunks,
     **from the far end backwards**, and never inside a margin of max(2 MB, 5 %) around the playhead while the player plays
     (`StreamTeePlan`). A paused, ended or absent player lets it fill everything. When only the margin is left it waits.
   - The cache's data source now blocks on a locked span (`FLAG_BLOCK_ON_CACHE`) instead of reading around it uncached,
     so a chunk being written by the tee never makes the player fetch the same bytes without storing them.
   - When the cache holds the whole stream it is copied out to the download's file and recorded as an audio-only copy.
     No yt-dlp, no second fetch, no player rebuild. The chunk fetch is interruptible, so cancelling the download frees it.
   - When it cannot (a video download was chosen, the stream is not a cacheable YouTube audio stream, the address has
     expired, or the copy fails), the download falls back to the ordinary strategy **after it says why** and, for the auto
     download, after the item stops streaming (point 2). The queue treats an item as teeable once its audio stream has opened
     through the cache (`PlaybackTee.teeableNow`), so it is not held then.
4. **The traffic is measured.** A counting listener sits on the cache's upstream and counts the network bytes of every
   stream by cache key (`PlaybackTee.networkBytesFor`). The tee's completion line says the file size, the bytes the tee
   fetched and the bytes the stream and tee fetched together as a multiple of the file (`dewidebug … x the file`), so a report
   can prove there was no duplicate fetch. Deferral, tee, fall-back and hold-off decisions are logged under `playback`, `tee`
   and `download`.

## Evidence

Instrumented, on `totum-api35`, a real ExoPlayer playing a 15.9 MB WAV through the real cache and tee over a counting
fake upstream (`AStreamIsFetchedOnceThoughItIsAlsoDownloadedTest`, 4 cases, all passing):

| case | file | network | refetched | tee fetched |
|---|---|---|---|---|
| keeps playing, fast connection | 15,876,044 | 15,876,044 (1.000x) | 0 | 262,144 |
| keeps playing, slow connection (the tee and player race) | 15,876,044 | 15,876,044 (1.000x) | 0 | 262,144 |
| player left after 4 s | 15,876,044 | 15,876,044 (1.000x) | 0 | 6,350,400 |
| player left, slow connection | 15,876,044 | 15,893,684 (1.001x) | 17,640 | 13,812,120 |

The 17,640 bytes are the unflushed tail of the cache sink when the player is released mid-read (its buffer is 20 KB).
The JVM model of the planner against a fake upstream that counts bytes per range (`StreamTeeFillerTest`) shows the same
for a 70 MB stream played from the start, resumed in the middle, and paused: nothing fetched twice.

Real ExoPlayer reads the whole file in one open-ended read (one range in the first two rows), which holds an unbounded span
lock; the tee's chunks then wait behind it (blocking, not uncached reads), so on a stream that is still loading the player does
the fetching and the tee adds almost nothing. That is the right outcome (one fetch), and the tee does the work when the player
has stopped or paused. It also means the download of a playing item completes when the player has loaded the file, not before.

## Consequences

- No double fetch of a playing YouTube item's audio, and no mid-play rebuild: the 1.3 s buffering of the report cannot occur
  for a healthy stream. A swap only follows a pause, a stall or a seek.
- The auto-download of the playing item now waits for the tee's evidence (the audio stream opening through the cache). For an
  item that streams but cannot be teed (podcast enclosures, whose cache key lives for one launch only; SABR and live streams,
  which bypass the cache), the download waits until the item pauses or stops. With the stream held, the rescue ladder has no
  disk copy for those items while they play (the premise of report 0.1.435); it still has the stream retries, the sound-only
  and SABR rungs.
- The tee's download shows as downloading until the player has loaded the whole file, then completes. The auto-downloader's
  lane waits at most `SETTLE_TIMEOUT_MS` (10 min) for it, logs that it gave up waiting, and moves on; the download carries on.
- `FLAG_BLOCK_ON_CACHE` makes the player wait at most one tee chunk (256 KB) when they meet. If a lock were ever stuck the
  player would wait; nothing in the tests shows one, and the tee's failures release their spans.
- `JsonDownloadStore.change` (shared by the app and the Pi) now writes the file first and publishes the new list only when the
  write succeeded; a failed write is logged and thrown to the caller, memory stays where the disk is.

## Not built, and why

- **A download made from the cache for a different format** (a video download, or an audio format other than the one the
  player plays) is not attempted: a tee must be the stream that is playing. These fall back to the ordinary strategy. A
  deliberate manual video download of the playing item still fetches its own bytes, as chosen; it is logged, not held back.
- **Podcast and other non-YouTube streams** are not teed (their cache keys are per launch and the length is not in the address),
  so by the consistency rule ([ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)) they follow point 2: not
  fetched twice while they stream, fetched at a pause or stop. Teeing them needs a stable key and a length from the response.
- **A gapless swap** (adding the file as a playlist entry at the same position, as in [ADR 20](0020-gapless-queue-the-player-holds-the-next-item.md))
  was not tried: the tee removes the reason to swap at all, and a healthy stream is left alone.
- **Unproven on the phone.** The instrumented evidence is a real player over a counting fake upstream; whether YouTube's real
  address keeps working for the tee's late chunks (it expires; the fall-back covers it) is for the next reports to say through the
  `tee` lines.
