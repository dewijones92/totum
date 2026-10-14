---
title: Next and previous in the queue, everywhere the player is
kind: adr
status: accepted
updated: 2026-10-14
---

# 47. Next and previous in the queue, everywhere the player is

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"there are places … where I can't go to the next item in the queue. I can fast forward …"*.
The queue lives in the app, so the player holds one item: the session advertised "previous" (which only
restarted the item) and no "next", so the lock screen and a headset's next key did nothing. The full player had
only ±10/30 s; the mini player only next.

## Decision

- `PlaybackQueue.playPreviousInQueue`: more than 5 s in, back to the start; otherwise the item before. Next and
  previous share one stepping loop (`QueueStepping.kt`).
- In the app: ⏮ ⏪10 ▶ ⏩30 ⏭ in the full player and the video page, ⏮ in the mini player beside ⏭, both from
  `LocalQueueSkip` provided once by the shell.
- In the session (`QueueSkipButtons`): the notification and system controllers lose the player's own
  next/previous commands and get ⏮/⏭ buttons in the back/forward slots instead; a tap, or a media key
  (`onMediaButtonEvent`), is sent to the app as `QUEUE_SKIP_REQUEST`, and the queue moves.

## Consequences

- Tests: `QueueGroupPlaybackTest` (previous), `QueueSkipAndBlackoutTest` (device). The notification and lock
  screen were not exercised by a test; check them on the phone.
