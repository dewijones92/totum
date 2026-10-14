---
title: A range that drips is asked for again from the byte it reached
kind: adr
status: accepted
updated: 2026-10-14
---

# 33. A range that drips is asked for again from the byte it reached

- Status: Accepted
- Date: 2026-10-14

## Context

`AnHourLongItemDoesNotRebufferTest` failed the instrumented CI job on every `main` run from 0.1.603 to
0.1.606, always stalling once at 19.41 s (± 4 ms). The per-test logcat of the 0.1.606 run shows why: the
two requests that open a direct-URL stream start together; one delivers at full speed, the other
**drips 280 KB in 21.85 s (~105 kbps)**. Playback uses up what the fast one brought and waits at 19.4 s
until the slow one lands, after which the same stream runs at ~43 Mbps. Nothing was wrong with the
network; one request was being starved by the server, and `ChunkedDataSource` (which already splits a
stream into bounded 2 MB ranges to avoid YouTube's open-ended throttle) had no way to notice.

## Decision

- `DripWatch` judges each open **network** range on the **time spent inside its reads only**, after 3 s of
  that: below a threshold it is a drip. Wall time was wrong (found in review the same day and confirmed in
  the soak logs: six re-asks at exactly 1024 KB): Media3's progressive loader blocks between reads after
  every ~1 MiB while the buffer is full, with the range still open, so every full-buffer pause, user pause
  or preload hold looked like a drip. The
  threshold is an eighth of the typical speed of recent ranges (the median of the last nine, shared by
  every source in the process), kept between 4 KB/s and 256 KB/s; before any history it is 32 KB/s.
- A drip closes that range and opens a new one from the byte it reached (`ChunkedRead.abandonRange`); the
  bytes the player sees are unchanged. At most two re-asks per range, then it is left to finish.
- Local files are never judged or measured: a disk read would make every network range look slow.
- Logged as `[chunked] range dripping: …KB in …ms (~…KB/s, below …KB/s); re-asking from where it
  reached (n of 2)` with the read's position, counted in `playback.dripsReasked`, and a re-asked range's
  final speed is logged when it finishes.

## Consequences

- A starved request costs about 3 s of its own reading time instead of holding playback until it finishes.
- Moving from fast Wi-Fi to a slow mobile network can re-ask a few ranges until the typical speed
  catches up (two per 2 MB range at most).
- Whether a fresh request is served at full speed is YouTube's choice; the first CI run after this
  ships is the evidence (the hour-long test has failed on every run since 0.1.603).
