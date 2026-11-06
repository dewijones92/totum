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
