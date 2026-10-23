---
title: A star for anything, kept here, and a Like on YouTube for YouTube items
kind: adr
status: accepted
updated: 2026-10-23
---

# 79. A star for anything, kept here, and a Like on YouTube for YouTube items

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-22: a ⭐ for any item. Asked on 2026-10-23 he chose that starring a YouTube video also Likes it on the
account (unstarring removes the Like), that the Starred list merges the account's existing Liked videos, and that
podcasts and torrents stay local. Stars must be in the Drive backup. Songs already wrote Likes to YouTube Music (ADR 32).

## Decision

- One seam, `Stars` (app `stars/`), for every pillar. Stars live in a built-in local playlist with a fixed id
  (`starred`, "⭐ Starred"), so they are in the Drive backup with nothing new to back up; it is hidden from the
  playlists list and the add-to-playlist picker (`observeYourPlaylists`), and has no rename or delete.
- `setStarred` writes the star here first, then, for an item whose id is a YouTube video id, the Like or its removal
  through `MusicFavourites.setSongLiked` (the rating write with its read-back). A refused Like leaves the star and says
  so. A YouTube item with no media URL is still starrable, by its watch URL.
- Every item's menu has ⭐ Star / Unstar (`StarAction`), which replaces the song-only Like row; a song's row still
  reads the account's like. The player's 👍 Like stars or unstars the playing item here too.
- Library → ⭐ Starred opens the local playlist page for `starred`: the local stars, then the account's Liked videos
  (`VLLL`, paged by `PlaylistPages`, which `PlaylistViewModel` now shares), without duplicates. Removing an item there
  unstars it, Like included.
- Restoring a backup merges into the device's own Starred list, and merges any same-named playlist's items rather
  than skipping it.

## Consequences

- A star on a podcast or torrent never leaves the phone except in the backup.
- Starred is the playlist page with two rules switched on, not a second list screen.
- Tests: `StarsTest`, `BackupServiceTest` (two restore cases, red first), `StarAnythingTest` (menu → star → Starred
  page, no YouTube call for a podcast), `MusicFavouritesScreensTest` (a song's Star likes it on the account). Verified
  on totum-api35: the Starred page lists the account's likes (`playlist VLLL loaded 15 item(s), more=true`).
