---
title: Casting fixes from the diagnostics of 7 to 11 November
kind: adr
status: accepted
updated: 2026-11-28
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

## Amended 2026-11-27: a phone crossover is not a TV event

`RemoteEndWatch.finishedOnTv` logged "… on the TV: Totum never saw X play there" on every phone gapless crossover, even with nothing casting,
so a report read as if a cast had misbehaved. It now says that only while a cast is active (the watch has seen a remote item or a refresh,
and has not been told the player went back to local). It still returns the same answer either way. Red first: `RemoteEndWatchTest`
"a crossover with nothing casting says nothing about the TV". Relay hangs on a locked cache span are in
[ADR 186](0186-one-fetch-per-item-a-playing-stream-is-its-own-download.md).

## Amended 2026-11-28: an MKV whose Tracks are not in the first 64 KB is read by range, and a Jellyfin item's own subtitles reach the TV

Real run on the LG, 28 November 06:14 (Pi `totum serve` v0.1.984): Spinal Tap II (a Jellyfin item, 1080p x265 10-bit, about 5.5 GB MKV)
cast as sound only, `its video is in a format Totum cannot read for a TV (MKV Tracks are not in its first 65536 bytes)`. The source
answers byte ranges (Jellyfin through the Pi answers 206); the reader gave up after the first 64 KB.

- **`MkvVideo.probe` follows the SeekHead.** The head's top-level elements are walked as before. `Tracks` (and `Info`, for the duration and
  time scale) are used from the head when they are wholly inside it; otherwise the SeekHead names their position, the element header is read
  by range (16 bytes), then the element (`Range: bytes=a-b`, at most 4 MB). The cluster index (`Cues`) was already read by range. A head
  whose last element is cut off, or whose first element is longer than the head, no longer ends the walk with an error.
- **The refusal stays, with its reason, where it must.** `MKV Tracks are not in its first 65536 bytes and its SeekHead names no position
  for them` when there is no SeekHead to say where they are (such a file has no named `Cues` either, so it could not be cut anyway), and
  `MKV Tracks are not in its first 65536 bytes and the source would not give the range at N for them (…); a source that answers byte ranges
  is needed` when the source cannot do ranges. Both reach `the TV gets sound only — its video is … (reason)` as before.
- **Not a walk.** A file with no SeekHead is not scanned element by element: it would still have no `Cues` to cut by, so the walk would
  find Tracks and then refuse anyway.
- **Logged** (`cast`, `dewidebug`): one line per probe with the positions the SeekHead named (`MKV SeekHead (segment data starts at S)
  names Info at …, Tracks at …, Cues at …; the head holds 0x…+0x…`) and one per range fetch (`MKV Tracks are not in the first 65536 bytes;
  the SeekHead names them at A, so bytes A-B (N bytes) were fetched by range in Mms`).
- Tests: `MkvTracksBeyondTheHeadTest` (red: `UnreadableLayout: MKV Tracks are not in its first 65536 bytes`), `CastStreamServerMkvTracksTest`.
  The fixtures are made in the test (`MkvFixtures`): the repo's small `avc.mkv` is taken apart and put together again with a 70 KB
  Void before Tracks, or Tracks and Info after the clusters, with a SeekHead and a rewritten `Cues` so every cluster position is right;
  the cut segments are byte for byte what the untouched file gives.
- **Captions of a streamed file.** A torrent's or other streamed MKV's own subtitle tracks are still not pulled out (they would need the
  whole file). A **Jellyfin** item's are: Jellyfin makes WebVTT of each text subtitle stream itself
  (`/Videos/{id}/{mediaSourceId}/Subtitles/{index}/Stream.vtt`). The Pi lists them (`JellyfinBrowse.castSubtitles`, from the item's
  `MediaSources[0].MediaStreams`; a picture-based stream such as PGS is named and skipped), `TargetStreams` puts them in the `CastItem`, and the one
  stream server serves each as `subtitles/N.vtt`, cleaned for a TV like any other. The address with Jellyfin's key is read by the Pi only, never
  handed to the TV. Not done: the phone's own cast of a Jellyfin item (it has no list of the item's streams; `JellyfinItem` carries none), and
  the friend's-house public route (its session is opened by the phone with the phone's item).
