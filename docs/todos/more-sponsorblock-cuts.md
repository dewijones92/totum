---
title: Cut more sponsor segments with SponsorBlock
kind: todo
status: proposed 2026-10-06 — to investigate; nothing below is verified yet
area: playback/downloads
updated: 2026-10-06
---

# "Investigate how to cut more ads using SponsorBlock — calling it even after the video downloaded?"

Dewi, 2026-10-06, while the tap-speed work was running.

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
