---
title: A download asks sound or video, and which quality
kind: adr
status: accepted
updated: 2026-10-23
---

# 77. A download asks sound or video, and which quality

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-22: choose audio, video and its quality when downloading, with a "remember" tick, and long-press asks
again. Until now ⬇️ on a row fetched the best full video and the sheet's two entries chose sound or video, never a
quality. On 2026-10-23 he dropped "Both": a video file plays with its picture off in Listen, so a second sound copy
only costs space.

## Decision

- One seam, `DownloadChooser` (`app/…/downloads/`), reached through `ItemActions.chooseDownload` for every list and the
  item sheet. An item with no picture (a podcast episode, a song) downloads at once; otherwise it asks:
  🎧 Sound · 🎬 Video best / 1080p / 720p / 480p, and "Remember for next time".
- The remembered answer is a setting (`download_choice`: `sound`, `video:best`, `video:<height>`); with one set, ⬇️
  downloads without asking and **press and hold** asks again. The sheet's "video" entry asks unless the remembered
  answer is a video.
- The height is a ceiling carried from `DownloadManager.download(…, maxHeight)` to every strategy: yt-dlp's selector
  becomes `bv*[height<=H]+ba/b[height<=H]/bv*+ba/b`, and the signed-in path takes the best quality at or below it
  (else the lowest offered).
- Six screens had their own copy of "download this" in their view models, which is how they skipped the question;
  they now all use `rememberChooseDownload()` and the copies are gone.

## Consequences

- A download's log line says `audioOnly=… maxHeight=…`, and the chooser logs whether it asked, why, and what was
  chosen or remembered.
- Music keeps downloading sound directly (an album's ⬇️ is always sound).
