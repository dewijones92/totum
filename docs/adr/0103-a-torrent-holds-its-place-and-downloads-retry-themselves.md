---
title: A stalled torrent holds its place, and downloads retry themselves
kind: adr
status: accepted
updated: 2026-10-31
---

# 103. A stalled torrent holds its place, and downloads retry themselves

- Status: Accepted
- Date: 2026-10-31

## Context

Two reports from 0.1.766 (2026-10-30). *"it just buffered then restarted???? I was like 20 mins in"*:
The Matrix (a torrent) stalled at 14:26 on TorrServer timeouts, the stall watchdog spent its two
rescues and then, as for any stream, "gave up and moved on" to the next film from 0; earlier the same
film had jumped from 31:31 to 0 by a seek nothing logged. *"the torrent downloads failed and can we
make sure it downloads a resilience and they retry … from the same place"*: the Matrix pack fetched
1 of 4; one stopped on a timeout with 1.72 GB kept and stayed failed (ADR 80 only restarts downloads
interrupted by the app stopping, and only at the next launch). Meanwhile the preloader held the
film's audio-only HLS playlist, re-reading it every ~6 s during the stall. Dewi: "make good decisions,
just crack on" — these are Claude's.

## Decision

- **A torrent never moves on mid-item.** `StallWatchdog(holdsItsPlace)`: once its rescues are spent,
  an item whose id starts `torrent:` keeps replaying from where it stopped (about every 20 s while
  playback is wanted) and logs "it holds its place … try N". Other streams keep ADR-era behaviour.
- **Downloads retry themselves.** `TransientRetries` in `DefaultDownloadManager`: a failure that is not
  permanent (`isPermanent`) is retried after 30 s, 2, 8 and 32 minutes (four retries), each resuming
  from the bytes kept, waiting for a usable network; a success resets the count, a cancel or delete
  stops it, and the plans are exposed (`retryPlans()`) for the Downloads page.
- **The preloader never holds an HLS playlist** (`preloadableUrl`): a torrent's audio-only playlist is
  warmed on the Pi (`warmAudio`) instead; its stream is preloaded.
- **Every seek says who asked**: the session logs each seek-type command with its controller (the
  app, the notification or lock screen, or a package such as Bluetooth); the app's own `seekTo` logs
  its calling frame. The 31:31 → 0 seek came from neither the app's previous button nor a logged
  path, so the next one will say.
- **The stall watch proves it is alive**: `main.watch` = pings, longest wait, stalls (ADR 100).

## Consequences

- A torrent with no peers at all now waits for ever rather than skipping; the person sees the spinner
  and can skip by hand. A dead YouTube stream still moves on after two rescues.
- A download with a transient fault costs at most four extra fetches, each resuming.
- Tests: `StallWatchdogTest` (holds its place, red first), `TransientRetriesTest` (3),
  `DefaultDownloadManagerTest` (retries and lands, red first; permanent not retried), `PreloadUrlTest`
  (2, red first), `MainThreadStallsTest` (alive vital).
