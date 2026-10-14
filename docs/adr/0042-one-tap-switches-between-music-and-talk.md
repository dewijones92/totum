---
title: One tap switches between music and talk, within the queue
kind: adr
status: accepted
updated: 2026-10-14
---

# 42. One tap switches between music and talk, within the queue

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"I want a way to toggle between music and nonmusic … save me going in to the queue ui each
time"*. His queue mixes podcasts and videos with music, and moving between them meant opening the Queue tab.

## Decision

- **"Music" is `MediaContentKind.MUSIC`; everything else is talk** (`Listening` in `:core:domain`), for both
  pillars.
- **One rule decides where a tap goes** (`switchTarget`, pure): the item of the other kind played last, if it is
  still queued; else the next one after what is playing; else the nearest above. Nothing is removed or
  reordered; the queue jumps, and the item resumes where it was. With no music queued, the **newest music
  action in Recently** plays (an album, radio, artist or music playlist); with no talk queued, nothing happens.
- **One switcher** (`ListeningSwitcher`) holds the last item of each kind, serves every surface, and logs what
  was playing, what it remembered and why it chose its target.
- **Everywhere** (Dewi's choice): the mini player, the full player's Up next header, the Queue header, and the
  media notification / lock screen. The icon shows the **target** (🎵 while talk plays, 🎙️ while music plays).
- **In the notification it takes an extra slot** (Dewi's choice): a Media3 `CommandButton` in the overflow
  slot, which Android 13+ draws where "next" would be; the session advertises no "next" (the player holds one
  item), so nothing is displaced. A tap reaches the service, which forwards it to the app's controller
  (`ListeningSwitchButton` → `PlaybackController.switchRequests`).

## Consequences

- Verified on `totum-api35`: notification 🎵 jumped from a video to the next music (queue place 7) and the
  button became 🎙️; the mini player's 🎙️ went back to the video left at place 1; the button shows in the Queue
  header and the full player's Up next.
- The memory of the last item of each kind is in memory only; after a restart the first switch takes the
  nearest item of the other kind.
