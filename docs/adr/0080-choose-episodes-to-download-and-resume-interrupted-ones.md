---
title: Choose episodes to download, and an interrupted download starts again by itself
kind: adr
status: accepted
updated: 2026-10-23
---

# 80. Choose episodes to download, and an interrupted download starts again by itself

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-22: download a whole torrent series or a queue group: a sheet listing every episode, all ticked, then
the sound / video choice; a few at a time; resumes if interrupted. On 2026-10-23 "Both" was dropped from the choice
(ADR 77). Before this, the queue group menu only switched its automatic sound download on or off, multi-select
downloaded at best quality with no choice, a torrent could only be played (every file queued), and a download the app
died during was left Failed for a manual Retry unless its item was still in the queue. A retry also lost the chosen
quality: the stored request only says sound or video.

## Decision

- One seam, `EpisodeDownloads` (app `downloads/`), and one sheet, `EpisodePicker`, hosted by the shell: every episode
  listed with its state (✅ on the phone, ⏳ downloading, ❌ failed), all ticked except what is already on the phone,
  Select all / none, and "📥 N of M on the phone". YouTube items get the ADR 77 choice (🎧 Sound, 🎬 best / 1080 / 720 /
  480, the remembered one preselected); a torrent file or podcast episode is always fetched as its file, since asking a
  torrent for sound fetched the whole film anyway. A song (music) is always fetched as sound, and a batch of songs
  asks nothing (review of 2026-10-23: songs play as sound until Watch, ADR 24).
- Opened from a queue group's ⋮ → ⬇️ Choose episodes to download…, from a torrent search result's ⬇️ (the torrent
  is prepared as for playing, and its playable files are offered), and, since 2026-10-23, from multi-select on every
  list: **⬇️ Download…** hands the selection (less anything already downloading) to the same sheet, titled "N selected
  items", in place of the old hold-to-confirm best-quality download (Dewi: *"batch download things like lists … audio
  or video … what quality"*). It is offered while any selected item is not on the phone as wanted.
- "A few at a time" is the download manager's own limit of four at once.
- **Every** download the app died during now starts again by itself at the next launch, with its variant and its
  height, at most twice and never on a metered network; after that it is left Failed for Retry as before. That stops
  the manager's own resumes looping; an item still in the queue is asked for again by the queue's automatic pass on
  each launch, as it was before (its attempt count is per session). An upgrade keeps the old copy's record
  throughout, so the old copy stays playable while the new one downloads, and an upgrade the app died during is
  cleared at start-up with the old copy kept (review of 2026-10-23). The chosen height is kept in `download-notes.txt` beside the downloads (`DownloadNotes`), not in Room, so no
  schema bump; Retry uses it too.

## Consequences

- Review of 2026-10-23: the multi-select sheet's title counts what it lists (playable, not already downloading); an
  audio-only copy of a video starts ticked when the choice is a video (`startsTicked`), so selecting audio copies to
  get their picture works as the old "download in full" did.

- Progress for a batch is the sheet itself when reopened, and the Downloads page; there is no separate notification.
- Tests: `MultiSelectTest` (the selection reaches the sheet with its title), `EpisodeDownloadsTest`, `DefaultDownloadManagerTest` (resume with the height, red first; the two-restart cap),
  `EpisodePickerTest` (ticked ones only, at the chosen height; one already on the phone starts unticked).
