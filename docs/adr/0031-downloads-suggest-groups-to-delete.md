---
title: Downloads suggest groups to delete, reviewed before anything goes
kind: adr
status: accepted
updated: 2026-10-13
---

# 31. Downloads suggest groups to delete, reviewed before anything goes

- Status: Accepted
- Date: 2026-10-13

## Context

Dewi, 2026-10-13: *"in the downloads section of the app [I want it] to suggest group to batch delet
… i.e. big files or files that I havent opened in over 30 days"*. The queue downloads everything in it
on Wi-Fi, so the phone fills up on its own. Asked, he chose four groups (not opened in 30+ days,
finished, over 500 MB, not in the queue), review then delete, nothing protected, and that deleting a
queued item's file also takes it out of the queue. Nothing recorded when a file was downloaded or last
opened: `playback_progress.updatedAtEpochMs` also moves when a position is copied in from the YouTube
account, positions under 5 s are never saved, and `play_history` keeps only 50 rows. yt-dlp's
default stamps a downloaded file with the server's date, so the file's own date cannot stand in either.

## Decision

- The download row records its own dates (database v27): `downloadedAtEpochMs`, written when a row
  becomes Downloaded and kept while it stays downloaded at the same path; and `openedAtEpochMs`, stamped
  by `DownloadManager.noteOpened` from the playback controller's `onPlay`, which every play start passes
  through (local file or stream, both pillars). Downloads already on the phone at the upgrade are dated
  at the upgrade, so their 30 days start then.
- One pure rule, `suggestCleanup` in `:core:domain`: "not opened" counts from the later of the download
  and the last open (a download with neither is never called unopened), exactly 30 days counts; "big" is
  over 500 MB in the same 1024-based megabytes the screen shows; "finished" is a Played progress state;
  "not queued" is absent from the current queue. A file can be in several groups; each lists its largest
  first and states the bytes it would free; empty groups are not shown.
- Cards at the top of Downloads open a review dialog with every file ticked. Only what stays ticked is
  deleted, and those items are taken out of the queue in the same action (`PlaybackQueue.removeItems`)
  so queue auto-download does not fetch them straight back.
- Logged under `[library]`: each change in the suggestions (group, count, bytes, how many downloads have
  no dates), a card opened, ticked versus kept, and per deleted file its size and both dates; under
  `[download]`, each open stamped.

## Consequences

- Nothing is deleted without a review; the existing multi-select bulk delete is unchanged.
- An open shorter than 5 s still counts as opened, unlike the resume position.
- Existing downloads cannot be suggested as unopened until 30 days after the upgrade.
- A file deleted while it is playing leaves the queue too, like removing the playing entry by hand.
