---
title: Every collection tile has a long-press menu
kind: adr
status: accepted
updated: 2026-10-15
---

# 55. Every collection tile has a long-press menu

- Status: Accepted
- Date: 2026-10-15

## Context

Dewi, 2026-10-15: *"in music … recently section … I want to be able to long press one and shuffle or start radio,
etc., or go to artist, go to album"*. He chose the full set per kind, everywhere a collection appears, opened by a
long press (a tap still plays).

## Decision

- One pure function, `pinMenuFor(pin, inRecent)` (`app/…/pins/PinAction.kt`), says which actions a tile has:
  - an album: play, shuffle, play next, add to queue, start radio, go to album, go to artist, save;
  - a playlist: play, shuffle, play next, add to queue, go to playlist;
  - an artist: shuffle all songs, artist radio, go to artist, follow (the id-only ones are hidden when only a name
    is known);
  - a radio: restart, play the song it started from;
  - a show: play, go to it; a Shuffle all: shuffle again, go to its channel when it has one; an artist's All songs:
    shuffle again, go to artist;
  - every kind: pin or unpin, add to home screen, and remove from Recently on a Recently tile.
- A song tile opens the ordinary song menu (`ItemActionSheet`) instead.
- `PinTileActions` carries them out through the existing seams: `PinPlayer` (its album and playlist loading
  now shared as `albumRun`/`playlistRun`), the queue, `MusicRadio`, `MusicFavourites`, `ArtistShuffle` and
  `RecentActions.forget`. Pages open through `LocalOpenMusicPage` and `LocalOpenSource`.
- The same menu is on Recently and Pinned (Music home and Library) and on album, artist and playlist cards
  (search, artist pages, the Music home).

## Consequences

- A new tile kind cannot be added without `pinMenuFor`'s `when` failing to compile.
- Tests: `PinMenuTest`, `RecentActionsTest`.
