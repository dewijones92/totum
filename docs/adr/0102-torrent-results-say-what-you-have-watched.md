---
title: Torrent results say what you have watched, and a tap resumes
kind: adr
status: accepted
updated: 2026-10-30
---

# 102. Torrent results say what you have watched, and a tap resumes

- Status: Accepted
- Date: 2026-10-30

## Context

Dewi, 2026-10-30: *"Have I viewed some of the video before … multiple videos in a folder. Have I
viewed one? Am I half in the middle of one? What other enhancements that apps like this have … Crack
on. I trust you."* The decisions here are Claude's, on that delegation. A torrent file was already a
stable item (`torrent:<hash>:<index>`, so its position is saved like any other), but nothing used it:
a result said nothing about what had been played, and a tap always started at the first file.

## Decision

- A result's progress comes from the saved play states of its hash (`TorrentProgress.chips`), shown
  first among its chips; a different copy of a played title (name key: words before the year or
  episode marker, plus it) is named as such.
- `TorrentsSeen` keeps the latest 500 prepared torrents (hash, name, playable files' labels) in
  `files/torrents-seen.json`, written atomically, so a pack's chip can name the episode and count the
  whole. It is not in Room (no schema bump) and not in the Drive backup.
- A tap queues the whole torrent as before and starts at the file in progress, else the first
  unwatched after the last watched, else the first (`resumeAt`, `playAll(startAt)`).
- The episode picker shows each file's watched / started state.
- A quality chip is read from the name (resolution, source, codec, HDR, audio).

## Consequences

- A result never played on this phone but watched elsewhere says nothing (torrent positions are not
  synced to any account).
- The "another copy" match is by name, so two different films sharing a title and year would match;
  it says "another copy", never "watched".
- Tests: `TorrentProgressTest` (7), `TorrentsSeenTest` (3), the quality case in `TorrentTrustTest`,
  `PlaybackQueueTest` (start part-way), `TorrentTrustChipsTest` on the device.
