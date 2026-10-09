---
title: Anything can be pinned; a pin plays at once, from the app or the home screen
kind: adr
status: accepted
updated: 2026-10-09
---

# 28. Anything can be pinned; a pin plays at once, from the app or the home screen

- Status: Accepted
- Date: 2026-10-09

## Context

Dewi, 2026-10-09: *"in the app (home screen??) I want to be able to set shortcuts .. e.g. a miss rachael
train video, e.g. pablo album X"*. Asked, he chose: shortcuts in **both** places (a Pinned row in the app
and icons on the phone's home screen); a tap **plays straight away**; **everything** pinnable (videos and
songs, albums and playlists, artists and radios, podcasts); the in-app row on the **Music home and
Library**; a video pin from the home screen **opens the full player**; a show pin plays its **newest
unplayed** episode.

## Decision

- One sealed `Pin` (Item, Album, Playlist, Artist, Radio, Show), keyed so the same thing is pinned once.
  A pinned item is stored as the backup's own `BackupItem`, so a pin and a backup cannot disagree about
  what an item is. Stored as JSON (`SharedPrefsPinStore`) and carried in backups beside the settings
  (`pins.v1`), merged on restore like everything else a restore brings.
- One `PinPlayer`, through the seams that already exist: an item `playNow`; an album (YouTube Music) or a
  playlist `playAll` as a group after the current item; an artist its Mix (by channel id when known, else
  the library tile's radio seed); a radio `MusicRadio.start`; a show its newest unplayed episode, from the
  stored episodes or a feed preview. Every decision is logged under `[pin]`.
- 📌 Pin / Unpin and 🏠 Add to home screen are in the shared long-press sheet (so every row has them) and
  on album, artist, playlist and show pages.
- Home-screen icons are pinned shortcuts (`ShortcutManagerCompat.requestPinShortcut`) with the pin's
  artwork; they open `totum://pin/<key>`, which `MainActivity` hands to `PinPlayer`, opening the player for
  a video. The newest four pins are also the app icon's long-press shortcuts.

## Consequences

- One feature serves both pillars; adding a kind of pin is one variant and one `when` branch, enforced by
  the compiler.
- A home-screen icon whose pin was removed in the app says so instead of doing nothing.
- Some launchers refuse pin requests; the pin then stays in the app and a toast says so.
