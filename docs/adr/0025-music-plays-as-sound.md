---
title: Music plays as sound unless you ask for the picture
kind: adr
status: accepted
updated: 2026-10-09
---

# 25. Music plays as sound unless you ask for the picture

- Status: Accepted
- Date: 2026-10-09

## Context

Asked whether songs from the Music tab should play as audio or follow the Listen-mode setting, Dewi chose
**audio by default**: less data, works with the screen off, and Watch is still there for a music video.

The queue already had a per-item, per-session "sound only" decision (`pictureGivenUpOn`, from the 403
rescue), honoured by routing, streaming and gapless arming, and lifted by Watch or a row's switch to video.

## Decision

- `MediaContentKind.MUSIC`, set on every item the music catalogue produces (songs in search, album and
  artist tracks, radio, Music-tab playlists). The kind is already persisted as a string column on every
  item table, so there is **no migration**, and an older build reads it as `STANDARD`.
- `PictureChoices` answers "sound only?" for the queue: a refused picture, or a MUSIC item whose picture
  has not been asked for. `routeNow` sees it as `audioPreferred`, so an audio-only copy on disk is used
  as well as the audio stream.
- `wantsThePictureAgain` (Watch, a row's switch to video) lifts it for that item for the session.
- The route line carries `kind=`, so a report says why an item played as sound.
- A music item has the bare YouTube video id, like feeds, channels, the resolver and shared links, so a
  song is one item whether it came from an album, search or a share (`OneSongOneIdTest`).

## Consequences

- A music video in the Music tab starts as sound; one tap on Watch shows it.
- Downloads from music pages are audio only, matching how they play.
- Search's VIDEO hits still use the watch URL as their id, an older inconsistency
  ([todo](../todos/search-video-hit-ids.md)).
