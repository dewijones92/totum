---
title: Casting fixes from the diagnostics of 7 to 11 November
kind: adr
status: accepted
updated: 2026-11-13
---

# 164. Casting fixes from the diagnostics of 7 to 11 November

- Status: Accepted
- Date: 2026-11-13
- Amends: [ADR 68](0068-cast-a-stream-the-phone-serves.md), [ADR 122](0122-a-cast-sends-the-best-rung-and-steps-down-itself.md),
  [ADR 81](0081-the-pc-screen-is-a-page-the-phone-serves.md), [ADR 157](0157-a-player-error-with-no-recovery-tells-the-user-and-offers-listen.md)
- Builds on: [ADR 72](0072-a-playback-cache-under-every-stream.md)

## Context

Dewi's diagnostics reports of 2026-11-07 to 2026-11-11 showed seven casting faults. Each was checked against the code
before it was fixed; what could not be fixed here is said below.

## Decision

- **Seek steps.** The phone's player, the cast player and the PC screen step 10 s back and 30 s forward from one place
  (`SeekSteps`). The TV had Media3's 5 s back, so Dewi pressed back four times.
- **Step-down.**
  - `StallsBeforeStepDown` counts a second buffering only when playback advanced 5 s or 10 s have passed since the last
    one (report: one TV starvation seen as two bufferings 67 ms apart stepped down for nothing).
  - `CastStallJudge` (pure, in `:core:cast`) also judges a buffering that never ends: still buffering 12 s after playing,
    not paused, counted from the later of its start and the last seek, steps down at once. This covers a stall that began
    inside the 4 s grace after a seek, which was never judged, so a 2160p cast froze for good. `CastStepDownWatch` ticks it
    every 2 s while the player buffers.
  - Not changed: `CastRungs` already steps down through every rung the TV can show (`showable`), not only the one sent.
    The one-rung case is an item that arrives with no ladder (a file, a torrent, a muxed stream); there is nothing to
    build a lower picture from, so it now logs that (`no quality ladder came with this item`, and the pictures the TV
    could have been sent when a step-down finds none). An earlier ladder in the manifest is not restored: the LG ignores
    it ([ADR 122](0122-a-cast-sends-the-best-rung-and-steps-down-itself.md)).
- **Relay throughput.** `VideoRelay` replaces the per-request proxy in `CastStreamServer`.
  - The range after the one the TV is reading is fetched when the source has been read to its end, held in memory
    (16 MB per range, 32 MB held, dropped after 2 minutes) and served from there when the TV asks for it. Only a closed
    range of at least 256 KB is read ahead. An open-ended request is relayed through a bounded read-ahead buffer (8 MB)
    so the source is not paced by the TV. A HEAD starts nothing.
  - On the phone the source is the ADR 72 playback cache (`CachedUpstream` over the same `PlaybackCache` and
    `ChunkedDataSource` chain the player uses, so YouTube streams are keyed by id, itag and length); on the Pi it is plain
    HTTP. A young URL's 403 is asked again (`PatientUpstream`), as the remux paths already did. The JVM's keep-alive
    connection pool is reused (Android's HttpURLConnection is OkHttp's own); no new HTTP client was added to the pure
    `:core:cast`.
  - Every video request is logged with its range, time to first byte, bytes, time, Mbit/s and whether it was aborted: the
    first three, then every slow one (under 8 Mbit/s, or a first byte after 1.5 s) or abort, then one summary line every
    30 s (`RelayLog`).
- **Zero-track containers.** A torrent MKV with its Tracks element after its Clusters (a 5.5 GB file whose SeekHead points
  at the end) left Media3 with no tracks: the phone's player was silent and could not seek, and the cast
  manifest failed with "no audio track". `TracksAtTheEndOfAnMkvTest` (instrumented, run on the emulator 2026-11-13)
  shows what Media3 does on a small local MKV built that way: it finds the track list, with or without a SeekHead entry
  for it, because it reads on until it reaches it. So the fault needs the size of the real file over a stream, where
  "reading on" means reading most of 5.5 GB before any track is known; that case was not reproduced here. Now `NoTracksWatch` reports a player that is ready, still has no tracks 1.5 s
  later and is not on a receiver, as `PlaybackProblem.NoTrackList`: the usual refusal toast says "this file's track list is
  at its end, so this phone can't read it. The Pi will repair it when it is filed" ([ADR 157](0157-a-player-error-with-no-recovery-tells-the-user-and-offers-listen.md)'s path).
  The error logs carry the track list (`TrackSummary`: count, type of each, seekable). The cast engine throws
  `NoReadableTracks` for it. Routing such an item through the Pi's ffmpeg, which follows the SeekHead, is not built here:
  the Pi side belongs to the filing work (repair at filing).
- **Subtitles to the TV.** The cast server accepts SubRip sidecars as well as WebVTT (the phone offers both; the server
  makes WebVTT of SubRip), so a streamed torrent's `.srt` files reach the TV. A streamed file's own embedded tracks cannot
  be pulled out without reading all of the file, so the log says so and only sidecars are offered; a downloaded file's tracks
  are still saved by `EmbeddedSubtitles`. `TorrentSidecarSubtitles` no longer drops a slow file list at 1.5 s: it keeps
  fetching, and the next lookup of that torrent waits up to 8 s for it.
- **PC screen.** `ScreenPlayer` accepts seek back and forward and next and previous (the media session refused Dewi's
  back and forward taps), and says 10 s and 30 s. The page has a seek bar with the time, which sends the position to
  the phone (`/screen/seek`), and the phone seeks that screen's player. A slider was chosen by the coordinator.
  The log spam "a report about load N ignored" is counted per pair of loads, logged once and summarised every 30 s
  (`StaleReports`); after ten stale reports in a row the current load is sent to the page again, once. A seek the session
  refuses because the command is not available is logged.
- **Telling the user.**
  - A cast whose manifest or sound keeps failing (three times in a minute) shows "❌ <TV> couldn't start “<title>”:
    <reason>" on the cast strip, and the Pi's target notice says the same (`CastFailures`, `CastTvPort.failed`). It clears
    when the item works. A failed answer no longer marks the stage done.
  - `PlaybackDiagnostics` makes no lost-tail claim while a receiver plays: the TV's buffer is not the phone's.

## Consequences

- Not seen on a real TV: the relay read-ahead against a throttled YouTube link, the step-down on a frozen 2160p cast, the
  seek steps on the Cast player, the seek bar on a PC screen in Edge, and the SubRip captions on the LG. See the feature doc.
- Memory: the relay may hold up to 8 MB per open request and 32 MB of read-ahead per cast session.
- What Media3 does with a track list at the end is recorded by `TracksAtTheEndOfAnMkvTest` (instrumented).
- `NoTracksWatch` speaks only once the player is ready and has no tracks. A player that never becomes ready while it
  reads on towards a track list at the end of a huge stream is still only "buffering"; naming that as a track-list
  fault would also blame every slow start, so it is left as it is. The cast side does name it (`NoReadableTracks`).
