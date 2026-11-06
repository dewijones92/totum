---
title: Cast the best quality only; Totum steps down itself
kind: todo
status: built 2026-11-06 (ADR 122); tests and device checks to follow
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

## Built 2026-11-06 ([ADR 122](../adr/0122-a-cast-sends-the-best-rung-and-steps-down-itself.md))

`CastScreen` carries per-codec heights and the one ranking; `CastLadder.ranked` + `CastRungs.choose` send the best
rung only (the best rung's URL, so the "fewer than 2 rungs falls back to the phone's URL" trap is gone);
`StallsBeforeStepDown` + `CastStepDownWatch` + `CastStreams.stepDown` drop one height after two stalls in 90 s and
re-send the queue at the same place; the PC page reports per-codec heights (`VP9:2160+AV1:2160`). Pinning tests
`CastLadderTest` and `CastRungsTest` were rewritten for one rung.

Still to do: tests (above); a real-LG check that `setMediaItems` at the same position resumes cleanly and that the
cast-progress work treats the playlist change as no end; the dead "going up to …" strip line and `castFetchHeight`
can go; the PC page's `MediaCapabilities` probe is untested in Edge.
