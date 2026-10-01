---
title: An audio download from the signed-in fallback was recorded as a full copy
kind: todo
status: fixed 2026-10-01 for new downloads; downloads already mislabelled are not repaired
area: downloads
priority: high
requested: 2026-10-01
updated: 2026-10-01
---

# An audio download from the signed-in fallback was recorded as a full copy

**Field report, 0.1.548, 2026-09-27 16:43, Dewi:** *"watch with video didn't work"*.

## Evidence (two reports from that day)

- **08:11:** the queue's automatic fetch of `nX0fgBL3sIM` starts `audioOnly=true`. yt-dlp then gets
  `HTTP Error 403`, the log says "trying the app's own signed-in path", and it fetches "from its
  resolved audio URL".
- **16:43:** `downloads.queueStates` says `=full`, and the route is "the downloaded video …
  `copy=full listen=false`". VIDEO mode then plays it with `hasVideo=false`.

## Cause

`PlayerBackedDownloadStrategy` only ever fetches audio. For a plain https stream it delegates to
`HttpDownloadStrategy`, which finishes with `Downloaded(path)`, and `audioOnly` defaults to false. So
the record said a full copy. Because routing prefers a full copy when watching, VIDEO mode chose a
file with no picture.

## Fixed

The player-backed strategy now marks whatever the plain fetch produced as `audioOnly = true`. Test:
`AFallbackAudioDownloadIsRecordedAsAudioTest`, seen red (`expected:<true> but was:<false>`) and then
green.

## Not done

Downloads already recorded wrongly stay wrong until they are re-downloaded. A one-off repair would
have to probe each "full" video file for a video track (`MediaMetadataRetriever`), about 400 files
on Dewi's phone.
