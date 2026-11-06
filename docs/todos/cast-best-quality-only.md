---
title: Cast the best quality only; Totum steps down itself
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Cast the best quality only; Totum steps down itself

Dewi 2026-11-05: "best quality please", for every cast target including the PC screen; 4K and AV1 when the
receiver supports it. PoCs on the LG OLED65C24LA (2026-11-05): it picks a rung at the start and never changes —
throttled to 2 Mbit/s it stayed on 1440p and stalled 4 times in 75 s; best-only 1440p and 2160p VP9 played; HEVC
2160p played; AV1 errors at any size; H.264 1440p and 2160p error.

Decided: send only the best rung the target can show; after two stalls Totum drops one rung itself and the strip
says so ("⚠️ Dropped to 1080p, Wi-Fi too slow"). Per-codec caps in `CastScreen` (LG: VP9/HEVC to 2160p, H.264 to
1080p, never AV1); this also changes phone format choice while casting (`CastAwareCodecSupport`). The PC screen
probes the browser's codecs (Edge plays AV1). Reuse `CastLadder.choose`; beware `CastRungs.kt:40` falls back to the
phone's flowing URL when fewer than 2 rungs — best-only must pass the best rung's URL. Tests: `CastLadderTest`,
`CastRungsTest`, `CastScreenTest`.

Whole-batch review: `CastScreen` grows into a `TargetProfile` that the codec ranking takes; the strip uses the shared `QualityLabel`.
