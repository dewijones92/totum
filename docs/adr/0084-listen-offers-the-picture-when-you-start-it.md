---
title: Listen mode offers the picture when you start something that has one
kind: adr
status: accepted
updated: 2026-10-23
---

# 84. Listen mode offers the picture when you start something that has one

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-23: *"if I'm in listen-only mode and I play like a video or a torrent … prompt me, do you want to switch
to video mode?"* Listen mode quietly plays a video as sound, so starting one gave no sign there was a picture to be had.

## Decision

- `PictureOffers` (`queue/`) decides, `PlaybackQueue.pictureOffers` holds it: an offer is made when **you** start
  something (`playNow`, `playAll`'s first item, a tap in the queue) while listening, and it has a picture
  (`pictureForSwitching` is YES, ADR 70). Never for music or a picture given up on (`PictureChoices.soundOnly`), never
  when already watching, and never on the app's own moves: auto-advance, gapless and Explore do not go through these
  paths, and the related-video fallback passes `byYou = false`.
- `PictureOfferStrip` (`ui/common/PictureOfferUi.kt`) shows it over the mini player for 8 s: "🎬 “…” has a picture. You
  are listening." with **🎧 Keep listening** and **🎬 Watch**, which is `ItemActions.watch`, the player's own switch.
  It stands while the queue's now-playing item is the offered one; the player's state is not used, because it still
  names the previous item for a moment after a new start (seen on the emulator: the first build closed every offer
  made while something else played).
- Every decision is logged under `[listen]`: offered, no offer and why, and how it closed (Watch, Keep listening, no
  answer in 8 s, playback moved on).

## Consequences

- Tests: `ListenOffersThePictureTest` (JVM, red first: offered on play now and play all; not for sound, music, the
  app's own start, or when watching) and `PictureOfferStripTest` (instrumented, red first: it stands while the player
  still reports the previous item, closes once the queue moved on, Watch switches and closes).
- `playNow`'s double-tap guard moved into `RepeatTaps` to keep `PlaybackQueue` under detekt's size limit.
