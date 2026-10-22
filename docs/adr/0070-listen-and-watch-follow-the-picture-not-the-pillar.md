---
title: Listen and Watch follow whether the item has a picture, not which pillar it came from
kind: adr
status: accepted
updated: 2026-10-22
---

# 70. Listen and Watch follow the picture, not the pillar

- Status: Accepted
- Date: 2026-10-22

## Context

Listen ⇄ Watch was offered only for the VIDEO pillar (YouTube). A torrent is modelled as a podcast handle
(`PlayHandle.Podcast`, "play this URL directly"), so a torrent of a TV episode had no Watch button in Listen mode and
no Listen button while watching. A podcast whose enclosure is a video had the same gap, and in Listen mode its picture
kept decoding. Dewi, 2026-10-22: *"Podcasts can be video. Torrents can be video. Torrents can be audio. Podcasts can
be audio. I think that needs a holistic approach."*

The pillar answers where an item comes from (labels, channel or show, watch history, transcripts). Whether it has a
picture is a separate fact, and only that one decides Listen and Watch.

## Decision

- `Picture` (`:core:domain`, `Picture.kt`) is YES, NO or UNKNOWN. A YouTube handle has one; anything else is judged by
  its address's file extension (`mkv`, `mp4`, `webm` … yes; `mp3`, `m4a`, `flac` … no; anything else unknown). The
  extension lists live there once; the cast server's "may this carry video?" reads the same lists.
- `offersListenAndWatch` (picture YES, or the VIDEO pillar) gates the row's and the sheet's switch, and the player's
  toggle through `QualityState.canListen`. Every route sets that state, a downloaded file and a plain stream included;
  the plain stream used to reach the player directly and leave the previous item's state behind.
- Listening to something with a picture takes its audio-only address when there is one (YouTube's audio format, the
  home server's remux of a torrent), and otherwise plays the same address with the video track switched off.
- UNKNOWN is settled by the player: `PlaybackState.hasPictureTrack` (the stream holds a video track, selected or not)
  shows the toggle, so a feed with extension-less links gets the switch once playing reveals a picture, and an audio
  one never shows a Watch button.

## Consequences

- One rule for every source, unit-tested in `PictureTest` and end to end in `TorrentListenModeTest` (both new cases
  failed against the old code: no switch for a torrent; a video podcast's picture still decoded in Listen mode).
- A podcast episode with an extension-less link shows no switch on its row or menu (what playing finds is not stored per item); the player's toggle appears once the stream shows a video track.
- Listening to a video podcast with no audio-only version saves decoding and battery, not data.
- The "switch the picture off" rule is one function, `listensWithThePictureOff`, used by the queue's play route and by
  gapless arming alike (the review of c7b1967a found arming had its own copy that kept the picture on).
