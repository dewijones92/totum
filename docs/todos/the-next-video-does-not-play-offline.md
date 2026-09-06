---
title: The next video does not play (offline) — resume waited on the account
kind: todo
status: fixed 2026-09-06 — the account read is bounded and skipped offline
area: playback
priority: high
requested: 2026-08-30
updated: 2026-09-06
---

# "why the next video not playing??"

Report 0.1.477, 30 Aug, no network. The queue advanced from index 2 to 3, routed to the downloaded
audio, logged `play 4wjHNgMLeyY from file:…` — and nothing followed: no transition, no ready, no
playing, through six more taps over the next minute. The report was sent 78s later with the player
still on the previous, finished item.

## Cause

`Media3PlaybackController.play()` fetches the resume position before `setMediaItem`, and
`AccountResumePositions` asked YouTube's `FEhistory` first, with no bound and no offline check. In
the same session every earlier play waited **7.4s** on `could not read watched positions: Unable to
resolve host` before its transition (12:55:10.838 → 12:55:18.222, and again 12:55:23 → 12:55:30). The
last six plays never got that failure line: the read hung (DNS resolution sits before OkHttp's
connect timeout), so the player was never handed the item. Each tap was a new play generation waiting
on the same dead read.

## Fix

The read is bounded (`REMOTE_WAIT_MS` = 1.5s) and skipped when the app already knows it is offline; a
slow read keeps loading in the background for the next play and the rows. The resume line records
which happened. Test written first and watched fail; also shown to fail with the bound disabled.

See [../features/progress-sync.md](../features/progress-sync.md).
