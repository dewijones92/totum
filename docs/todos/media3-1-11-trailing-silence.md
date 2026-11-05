---
title: Media3 1.11 plays the silence at the end of an item
kind: todo
status: waiting
updated: 2026-11-05
---

# Media3 1.11 plays the silence at the end of an item

Media3 1.11.1 shipped in v0.1.856 (2026-11-04) and was rolled back to 1.10.1 the next day. With skip-silence on,
the pauses inside an item are still cut, but the silence after the last sound is played in real time before the
item ends. `SilenceIsReallyCutTest` (CI instrumented tests) caught it on the push, the rerun and the nightly run:
the 24 s fixture (eight 1 s tones, 2 s gaps) ended at 8.5 s on 1.10.1 and at 10.5 s on 1.11.1; the position logs
show the last 2 s gap playing at 1x (22107 → 24009 ms over ~2 s). Media3's own `SilenceSkippingAudioProcessor`
constants are unchanged and the app uses its own `SilenceCuttingAudioProcessor`, so the difference is in how 1.11
ends the stream (the sink or renderer waiting for the stream's length, or playing what the cutter drops at end of
stream) — not yet pinned down.

1.11.1 also moved `MediaExtractorCompat` to `androidx.media3:media3-inspector` (`androidx.media3.inspector`) and made
`ParsableByteArray` reads fail on the plain JVM (it reads `Build.FINGERPRINT`); the framer already copies bytes
without that path (`takeInto`).

Renovate skips exactly 1.11.1 (`allowedVersions` in `renovate.json`); a later Media3 comes as a PR and its CI run
decides. To take one: the instrumented tests green (that test especially), then the soak.
