---
title: Switching to video is made fast by caching and readying, not by changing the extractor
kind: adr
status: accepted
updated: 2026-10-06
---

# 11. Switching to video is made fast by caching and readying, not by changing the extractor

- Status: Accepted
- Date: 2026-10-06

## Context

Dewi: switching from audio to video, and opening a channel, felt slow; "feel liberated to make it
faster by changing ANYTHING". Measured on his Pixel 7 (0.1.564): downloaded audio → "Watch with video"
took 16.7 s to the first picture, 13.8 s of it a yt-dlp extraction. The player joined 1080p in 1.4 s.
The step timeline on the emulator split a cold extraction (26.8 s) into a QuickJS solve of YouTube's
JS challenge (14.6 s, with the solver's preprocessed-player cache OFF) and eight network requests made
one after another (~10 s). The cache was only switched on by the SABR path (`_n_solver`).

## Decision

1. **The solver's preprocessed-player cache is on for every extraction**, pruned to the current player
   build after each one. The next extraction of any video on the same build skips the solve: 26.8 s →
   9.5 s on the emulator.
2. **A resolve is trusted until 30 minutes before its stream URLs expire** (their `expire=`), capped at
   5 hours, instead of a flat 10 minutes. A URL with no stated expiry keeps the 10 minutes. A dead
   stream is still `forget()`-ed and re-resolved, as before.
3. **A YouTube video playing from its downloaded audio copy has its video resolved in the background**,
   on an unmetered network only (the same rule as the next-up prefetch), whether it came from the
   queue or the Library. Only the resolve: no video bytes are preloaded.
5. **The player script and its solved data are shared across extractions** (module-level dicts handed
   to each new extractor, two player builds kept), so no extraction downloads the player twice. The
   YoutubeDL itself is not shared, because extractions overlap.
6. **web_embedded alone is asked first; every client only if that finds nothing playable.** On 11 videos
   twice (made-for-kids included) it matched the full list's best and best-durable height 22/22 at
   2.25 s against 2.95 s median, and on the emulator it turned "6 qualities (0 durable)" into
   "6 qualities (6 durable)". `android` stays in the fallback because made-for-kids content once played
   through it alone (2026-07-30). The steps line says which path ran. Downloads keep the full list.
7. **The engine is warmed 10 s after launch** (Dewi chose it: ~80 MB more memory for a session's first
   video not paying ~2.7 s of Python and JS start-up). Never at launch, and never under
   instrumentation, so test timings do not move. `warmUp()` is abstract on `YtDlpEngine` so no wrapper
   can swallow it.
8. **SponsorBlock is asked alongside the extraction**, from the id in the watch URL, instead of after
   it: ~1.5 s off every resolve on the emulator.
9. **Videos are looked up before the tap** (Dewi: "yes go with that order"). `ReadyAhead` takes wishes
   (a menu opened, the next item in the queue, an audio copy playing), keeps the four newest, and works
   through them one at a time on an unmetered network. It replaced `PictureReadier`, so there is one
   readying seam. On the emulator, opening a menu and then tapping "Play once" hit the cache.
4. **The player offers Watch for a downloaded audio copy**, using the same explicit "watch" item action
   as the row, not the mode toggle (which would flip a video-mode user to audio).

Not decided here (open, Dewi's call): moving the challenge solve off the phone (a decoder on the Pi,
as PipePipe uses its own server), reusing one YoutubeDL across extractions, asking fewer player
clients, or replacing yt-dlp on the device.

## Consequences

- An extraction per video played from an audio copy on Wi-Fi, run at background priority.
- 4.2 MB of preprocessed player on disk, one build at a time.
- A resolve can be served up to ~5 hours old; an IP change (Wi-Fi → mobile) can make it 403, which
  the existing stream recovery handles by re-resolving.
- `extract steps —` (with `solver player cache on|off`), `readying the picture of …`, `cache hit … trusted
  for Nm more` and `[latency]` lines say in a report whether each part worked.
