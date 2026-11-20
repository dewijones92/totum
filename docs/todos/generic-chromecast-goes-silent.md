---
title: 📺 Video casts to the generic Chromecasts go silent (Kitchen TV, Living Room TV)
kind: todo
status: built 2026-11-20, Batch C (unit tests green; nothing run on a real generic Chromecast)
batch: C
priority: high
updated: 2026-11-20
---

Reports of 14 to 17 November (v0.1.927): both TVs (model "Chromecast") fetch `manifest.mpd`, the VP9 video's init and index, then
nothing. Audio-only casts work. Decision, reuse and consequences: [ADR 179](../adr/0179-a-silent-cast-moves-to-the-next-codec-and-an-unmeasured-tv-gets-h264-first.md).

- Built: H.264 first for an unrecognised model; the 8 s start watchdog and the codec step-down; the refusal remembered per model once
  the other codec plays; the receiver's status and errors logged; begun and in-flight relay requests logged; the route-selected
  watch with Retry; `NoTracksWatch` quiet while playing; a DNS failure waits for the network.
- Found while checking on the emulator: the ladder held one codec per height, so the codec step had nothing to step to; fixed with
  `VideoQuality.alternates`.
- **Reused:** `CastStepDownWatch`'s re-send (now `resend`), `CastTvPort`/`TvVideos`/`CastStatus`' extras for the strip line,
  `CastReach`'s timer (`castLater` is its old private one, moved), `CastRungs`/`CastLadder` for choosing the next rung,
  `SnackbarOffers` for the Retry, `NetworkStatus.awaitOnline` for the wait.
- **Owed on a real generic Chromecast** (nobody here may cast to the real TVs): does H.264 first make both TVs play; is 8 s right;
  does the strip line read well; does Retry recover a Dynamite failure; what does the receiver say (the new status and error lines).
- **Scale (Dewi's state, 5,857 queue items, 1,623 subscriptions, 509 downloads):** nothing here walks the queue or the subscriptions. The
  relay heartbeat touches only the open cast sessions (at most six), the refusal store is one small prefs file, and the resolver
  waits per resolve, not per queue item.
