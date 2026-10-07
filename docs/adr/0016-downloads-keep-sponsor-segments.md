---
title: Downloads keep their sponsor segments; playback skips them live from the latest list
kind: adr
status: accepted
updated: 2026-10-07
---

# 16. Downloads keep their sponsor segments; playback skips them live from the latest list

- Status: Accepted
- Date: 2026-10-07

## Context

A video download cut its SponsorBlock segments out of the file (yt-dlp's ModifyChapters). SponsorBlock is
crowd-sourced, so segments for a new upload often arrive hours or days after it was downloaded, and a cut
file could never pick them up. Files on disk also got no live skipping at all: both file routes
(`VideoFile`, `AudioFile`) played with an empty segment list. Dewi, 2026-10-07: *"checking for new sponsor
segments after it's been downloaded"*. Of re-cutting files, mapping times on cut files, or not cutting, he
chose **not cutting**, and to leave files already cut alone.

## Decision

- New video downloads are not cut. `EngineDownloadStrategy` records what it did
  (`DownloadState.Downloaded.sponsorSegmentsCut`), so the row states the fact rather than a later reader
  guessing it.
- Database v25 adds `downloads.sponsorSegmentsCut` and `downloads.skipSegments`. The migration marks
  **every existing row as cut**, so no old file is ever skipped on a timeline that no longer matches.
- `DownloadedMedia.segmentsToSkip` (domain) is the one rule: an uncut YouTube video's stored segments,
  else none. `realCopyFor` puts them on `LocalCopy`, and the queue plays both file routes with them.
- `SkipSegmentsOnDisk` refreshes them: on download completion and on every play from disk it asks
  SponsorBlock (`FreshSkipSegments.lookup`), stores a changed answer and hands it to the player
  (`PlaybackController.updateSkipSegments`). A 404 is an answer ("none"); a failure to ask is not, so an
  offline play keeps the stored list rather than overwriting it with nothing.

## Consequences

- Downloaded videos are a little larger (the sponsor seconds stay in the file) and skip like streams do,
  including segments submitted after the download, and offline from the last list stored.
- Downloads made before v25 keep their cut timeline and get no live skipping.
- ffprobe stays bundled: nothing was checked yet for other uses, and removing it is a separate change.
- Every lookup decision is logged under `sponsorblock` (`… on disk: segments 1 -> 2, stored`, `lookup failed
  (…); keeping N stored`, `not looking segments up, its sponsors were cut at download`), and a finished
  download logs `sponsorSegmentsCut=`.
