---
title: Smooth video at any speed
kind: feature
status: shipped
area: playback
updated: 2026-11-07
---

# Smooth video at any speed

**Ask (Dewi, 2026-11-06):** 2x video on his Pixel 7 "just doesn't look smooth". It showed 8.5 frames a second.

**What it does:** every video's frame rate reaches the decoder before it is configured, so a hardware decoder runs at
the clock it needs (60fps at 2x is 120 decoded a second), and the screen is asked for the refresh rate that shows the
frames evenly. Decision and measurements: [ADR 139](../adr/0139-the-decoder-and-the-screen-are-told-the-frame-rate.md).

**Seams:**
- `core/playback/…/FrameRateStamp.kt`: `StampsFrameRate` wraps every extractor (`totumExtractors()`);
  `FrameRateStampingTrack` holds a rateless video track's format and first 12 frames, `frameRateFrom` judges the rate.
- `lib/innertube/…/LiveDash.kt`: the live manifest's `frameRate`.
- `app/…/ui/common/VideoSurface.kt`: the one video surface; `evenRefreshFor` picks the display mode.

**Logs:** `[video] frame rate … worked out …`, `[video] frame rate not worked out …`, `[video] screen refresh
preference …`, and the existing `[format] video … @Nfps` and `[playback] dropped N frames …` lines.

**Tests:** `FrameRateFromSampleTimesTest` (including whole-millisecond WebM/MKV times, a clock that does not start at
zero and reordered frames), `FrameRateStampingTrackTest`, `StampsFrameRateTest` (the wrapper over a Matroska-shaped
extractor: video held and stamped, audio never held, an early end, a seek, a stated rate), `EvenRefreshTest`,
`LiveDashTest`. Not covered: the real `MatroskaExtractor` on a real WebM (its `SparseArray` needs a device). Verified on
the Pixel 7 by SurfaceFlinger frame times and the player's dropped-frame counter (ADR 139).
