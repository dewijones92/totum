---
title: Blocked videos download with picture, through the signed-in path
kind: adr
status: accepted
updated: 2026-10-16
---

# 52. Blocked videos download with picture, through the signed-in path

- Status: Accepted
- Date: 2026-10-15

## Context

Downloads are yt-dlp first, with the signed-in path (`PlayerBackedDownloadStrategy`) as the fallback.
Two things stopped a bot-blocked video from downloading properly. A "not a bot" refusal was not one of
the failures that `deservesAnotherRoute`, so the fallback never ran. And the fallback only ever fetched
the sound, even when a full video was asked for. Dewi chose "Video + audio" for blocked downloads.

## Decision

- "not a bot" and "bot check" count as refusals that deserve another route (`DownloadFailureTest`). During a
  bot back-off (ADR 51), `PoliteYtDlpEngine.download` fails at once with that wording, so a blocked video
  goes straight to the signed-in path without a refused call.
- When a full video is wanted, the signed-in path takes the best quality that playback resolves. It
  fetches the video and its separate audio in 10 MB ranges (`RangedFetch`, sized by the link's `clen`),
  merges them into Matroska with the bundled ffmpeg (`YtDlpEngine.mergeStreams`: `-c copy`, no
  re-encode) and records a full copy. A single muxed stream needs no merge. If any step fails, it
  falls back to the sound alone, as before, and logs why.
- **The sound alone is fetched in 10 MB ranges too** (2026-10-16). It used to go through the podcast downloader as one
  open-ended GET, which YouTube paces at about playback speed: three reports from Dewi's Pixel 7 on v0.1.639 ("slow to
  download") crawled at about 2% per 30 s while the phone measured ~45 Mbps. On `totum-api35` the same video went from
  ~33 KB/s to 48,827,608 bytes in 9.3 s (5,111 KB/s). The finish line now states the bytes, the seconds and the rate.

## Consequences

- The best quality can be 2160p, so files can be large. The caps in the streaming settings do not apply to
  downloads.
- Tests: `ABlockedVideoDownloadsWithPictureTest` (ranges, merge, fallback on a failed merge),
  `ASoundOnlyDownloadIsFetchedInRangesTest` (the sound never takes the single open GET),
  `PoliteYtDlpEngineTest` (no yt-dlp download during a back-off), `DownloadFailureTest`.
