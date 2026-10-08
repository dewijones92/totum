---
title: A fresh YouTube URL is waited for, a preloaded source is played, and a copy on disk is used first
kind: adr
status: accepted
updated: 2026-10-07
---

# 13. A fresh YouTube URL is waited for, a preloaded source is played, and a copy on disk is used first

- Status: Accepted
- Date: 2026-10-07

## Context

Two reports from Dewi's Pixel 7 on 0.1.573 (the build with [ADR 12](0012-solve-youtube-challenges-in-v8.md)):
"video loading as still slow?" and "did novara just buffer??? I thought it was offline??".

- Extraction had dropped to 1.9–2.4 s, but three of four taps took 3.6–5.9 s to the first frame.
  YouTube's `web_embedded` stream URLs answer **403 for ~4.1–4.6 s after they are issued** (measured
  from `expire=` − 6 h on 6 videos; 5/5 by the player JSON), then 206 with the same URL. Builds before
  ADR 12 never saw it: the slow QuickJS solve ran after the URL was issued, so it was old enough by the
  first request. Media3's default backoff (0 s, 1 s, 2 s, then fatal after the fourth failure) turned
  the window into seconds; on the emulator it went fatal, was filed as `Rejected`, re-resolved into
  another fresh URL, and gave up the picture.
- The Novara video was streaming on a lease issued six hours earlier while its audio sat downloaded.
  A seek hit the expired lease, and recovery re-extracted (2.3 s) before routing to the copy anyway.
- The next-item preloader held ~30 s of bytes the player never used: a `DefaultPreloadManager`'s
  source is only reused if handed to the player, and nothing did.

## Decision

1. **A 403 on a URL younger than 15 s is retried once the URL should be valid** (`YoungStreamUrl`, age
   read from `expire=`), then +0.75 s and +1.5 s, inside Media3's retry budget, instead of on the default
   backoff. Any other failure keeps the default policy. "Should be valid" was a fixed 4.8 s until
   2026-10-07; it is now **learned** (`YoungUrlWindow`): the latest age that still got a 403 plus 0.3 s,
   capped by the earliest age that got through, over the last 6 hours, falling back to 4.8 s with no
   evidence and never past 14 s. Reason: report 0.1.577 (2026-10-07) showed the window had grown, with all
   five fresh URLs refused at 4.85 s and 5.66 s and playing only at ~7.2 s, so every slow start paid two
   wasted retries. Each 403 line now carries the learned window, the first success per URL is logged
   (`young stream accepted at …`), and `playback.youngUrlWindow` holds it for reports.
2. **The player is built through the preload manager** (`buildExoPlayer`), sharing its looper and one
   `DefaultLoadControl` with separate byte budgets for playback and `PlayerId.PRELOAD`. A nomination
   carries the video and the separate audio URL, and the service's source factory hands the held
   source to the player when the same item and streams are asked for.
3. **A replay whose route is a copy on disk plays the copy without extracting first.**
4. **When the streaming item finishes downloading, playback moves to the copy at the same position**,
   if `routeNow` would choose it (so an audio-only copy does not replace a picture being watched).
5. **The last six route decisions, with their times and inputs, are kept in `playback.recentRoutes`**,
   so a report sent hours later can say why an item streamed.

Menu-open buffering (Dewi first chose it) was dropped: no byte arrives before the URL is valid, and once
it is, the first frame takes ~0.3 s anyway. Only looking up earlier can beat the window.

## Consequences

- A tap whose lookup finished less than ~4.5 s earlier still waits for the window, now without failing.
- Queue advances start from held bytes on Wi-Fi; `playback.preloadsUsed` and `preloadsWasted` count it.
- Amended by [ADR 20](0020-gapless-queue-the-player-holds-the-next-item.md) (2026-10-07): with the gapless
  queue on, the next item is put in the player's own playlist instead, so the preloader serves only what
  gapless leaves alone.
- Diagnostics: `403 on a stream issued Nms ago … retry k in Nms`, `playing X from the source held for
  Nms`, `replaying X from the copy on disk …`, `X finished downloading while streaming: switching …`.
- Tests: `AYoungStreamUrlIsWaitedForTest`, `AYoungUrlPlaysWhenYouTubeAcceptsItTest` (instrumented, with
  a control on the default policy that fails), `PreloadCommandReachesServiceTest`,
  `ADeadStreamWithACopyPlaysTheCopyAtOnceTest` (red first), `AFinishedDownloadTakesOverTheStreamTest`,
  `RecentRoutesTest`.
