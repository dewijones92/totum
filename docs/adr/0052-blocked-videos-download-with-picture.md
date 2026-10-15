---
title: Blocked videos download with picture, through the signed-in path
kind: adr
status: accepted
updated: 2026-10-15
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

## Consequences

- The best quality can be 2160p, so files can be large. The caps in the streaming settings do not apply to
  downloads.
- Tests: `ABlockedVideoDownloadsWithPictureTest` (ranges, merge, fallback on a failed merge),
  `PoliteYtDlpEngineTest` (no yt-dlp download during a back-off), `DownloadFailureTest`.
