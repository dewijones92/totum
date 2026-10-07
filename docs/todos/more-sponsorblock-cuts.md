---
title: Cut more sponsor segments with SponsorBlock
kind: todo
status: built 2026-10-07 (ADR 16) — downloads stop cutting, files on disk skip live; old cut files left alone (Dewi)
area: playback/downloads
updated: 2026-10-07
---

# "Investigate how to cut more ads using SponsorBlock — calling it even after the video downloaded?"

Dewi, 2026-10-06, while the tap-speed work was running.

## Direction agreed (Dewi, 2026-10-07)

Asked again: *"checking for new sponsor segments after it's been downloaded … if I've downloaded a week
ago maybe since then"*. Of the three options (skip live on the cut file with a time mapping; re-cut the
file with ffmpeg; stop cutting), Dewi chose **stop cutting at download**:

- Downloads keep the whole video. yt-dlp's ModifyChapters cut (and the ffprobe it needs) is no longer
  run for new downloads.
- Every play, streamed or downloaded, skips live from the **latest** SponsorBlock list, through the
  one existing skip seam (`Media3PlaybackController`'s ticker). So a segment submitted a week after the
  download is skipped on the next play.
- The list is **cached per video** so a downloaded item still skips offline, and refreshed when online.
- Files already downloaded *with* cuts are **left alone** (Dewi, 2026-10-07): migration v25 marks every
  existing download as cut and they get no live skipping.
- Check whether ffprobe is still needed by anything else before removing it from jniLibs (~7 MB per ABI).

## Questions to answer (each a hypothesis until checked against the code and a real item)

- **Segments submitted after the download.** SponsorBlock is crowd-sourced, so segments for a new upload
  often arrive hours after it goes up. A download cuts the segments known *at download time*
  (yt-dlp's ModifyChapters). Is a later lookup ever made for a downloaded item? If not, re-ask
  SponsorBlock when the item is played (or periodically while it sits in the queue) and skip the new
  segments at playback.
- **Is playback-time skipping applied to downloaded copies at all?** `PlaybackQueue.route`'s
  `AudioFile`/`VideoFile` branches call the controller with a local path; check whether they pass skip
  segments, or only the streaming route does. If a file's segments were cut at download time, its
  timeline differs from SponsorBlock's, so skipping a downloaded file needs the CUT segments subtracted
  (or the item marked as already cut).
- **Categories.** Defaults are sponsor, selfpromo and interaction. Are intro/outro/preview/filler/
  music_offtopic worth offering more prominently, or per channel?
- **Coverage gaps.** How many played videos had zero segments, and how many gained some later? The
  `[sponsorblock] <id>: N segment(s) to skip` lines in reports can measure this.
- **Beyond SponsorBlock.** YouTube chapters titled "Sponsor"/"Ad"; the "most replayed" heatmap; DeArrow
  is titles only. Note what each could add before building anything.

## Done when

Each question has an answer from the code or a measured report, and any change ships with a test and
an `item-end`-style log line that says, per item, how many seconds of sponsor were skipped and from
which source.
