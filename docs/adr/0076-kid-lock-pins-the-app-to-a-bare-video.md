---
title: Kid lock pins the app to a bare full-screen video
kind: adr
status: accepted
updated: 2026-11-01
---

# 76. Kid lock pins the app to a bare full-screen video

- Status: Accepted
- Date: 2026-10-22

## Context

Dewi, 2026-10-22: hand the phone to a child with one video playing and nothing else reachable, and a home-screen icon
that opens straight into it locked (Peppa Pig). His choice: Android's screen pinning (unpinned with the phone's PIN),
plus Totum's own controls hidden, full screen.

## Decision

- One state, `KidLock` (`container.kidLock`). Locking closes the full player and covers everything with
  `KidLockedStage`: the video alone on black, every touch consumed, back ignored and logged. It reuses the player's
  `FullscreenEffect` for landscape and hidden bars; a second owner of the window's orientation fought it (the full
  player's effect reset it to portrait when it closed).
- `KidLockWindow` (the activity) keeps the screen on and asks Android to pin the app 1.5 s after locking, once the
  screen has turned: Android's own "App is pinned" confirmation closes on rotation. It checks the pin every second;
  when a pin that was on goes away (Android's unpin gesture, which asks for the phone's PIN when "Ask for PIN before
  unpinning" is on), the lock lifts by itself.
- With app pinning off (Android's default) or declined, Totum alone stays locked; after 30 s a hint says how to turn
  pinning on, and holding the top-left corner for 3 s unlocks. That corner does nothing while Android's pin is on.
- While locked the shell's "open the player" does nothing, so rotation, the mini player and shortcut requests cannot
  reopen it underneath.
- Entry points: a 👶 Kid lock tile in the player, and "👶 Add to home screen, locked for a child" on any pin that can
  show a picture (not albums, artists, radios or artist songs); that shortcut plays its pin and locks
  (`totum://pin/<key>?lock=1`). What plays next is the queue's ordinary next item.

## Kid mode (2026-11-01)

Dewi: *"when it's pinned, I don't want it to skip silences, and I don't want to play it more than 1x speed … I want
it to loop … I want the mode to be obvious … not permanently overlaid … no play controls … brightness slider, but the
volume … by the keys on the side."* He chose: loop what the icon started; a tap shows the badge and brightness.

- While locked (`KidMode`, `KidRules`): speed is held at 1× and silence skipping off, whatever a show's own settings
  say, and both come back on unlock; auto-play is on and Explore off; the repeat is the playing item's queue group
  when it has one (a playlist, channel or album the icon started) and the item itself otherwise, so nothing else from
  the queue plays.
- A tap shows "👶 Kid mode" and a brightness slider (the window's brightness, cleared on unlock) for 3 s; nothing
  else is drawn over the video; volume is the side keys only. An ongoing "👶 Kid mode is on" notification.
- Checked on totum-api35: lock by broadcast, pinned, speed and skip held, the tap badge and slider over the video.

## Consequences

- Verified on totum-api35: tile and broadcast lock, landscape, back ignored, the corner unlock with pinning off, and
  with pinning on Android's confirmation, PINNED, then an unpin lifting the lock and returning to portrait.
  `KidLockScreenTest` (tile → stage, player closed), `KidLockTest`. A debug broadcast `DEBUG_KID_LOCK` drives it.
- Not tested on Dewi's phone: whether his launcher accepts the locked shortcut, and whether pinning asks for his PIN
  (a system setting).
