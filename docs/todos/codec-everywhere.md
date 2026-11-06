---
title: The codec beside every quality; AV1 first when the hardware decodes it
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# The codec beside every quality; AV1 first when the hardware decodes it

Dewi 2026-11-05: show codecs with qualities; prefer AV1. Decided: a fixed rule, no setting — hardware AV1, then VP9,
HEVC, H.264; never software AV1 (`VideoCodecSupport.kt`). Labels read "1080p · AV1" everywhere (`VideoQuality.kt:133`,
`PlayerStreams.kt:358`, download chooser, cast strip; `CastRung.label` is the precedent). Downloads honour the device's
decoders (today yt-dlp's default sort can pick AV1 on a phone without hardware AV1).

Whole-batch review: the ranking already exists (`codecPreference` in `VideoCodecSupport`); only the labels, one `QualityLabel(height, codec)` shared with the cast strip, and the download path are new. The ranking takes a target profile so the LG (no AV1) and Edge (AV1) differ.

Progress 2026-11-06: `QualityLabel(height, family)` is in `:core:domain` ("1440p · VP9") and the cast strip uses it;
`VideoCodecSupport.preference` is the one ranking (the target's for a cast). The player's quality menu
(`VideoQuality.kt`, `PlayerStreams.kt`) and the download chooser still show plain heights, and downloads still do not
honour the device's decoders.
