---
title: The codec beside every quality; AV1 first when the hardware decodes it
kind: todo
status: built; tests written
batch: A (2026-11)
priority: high
updated: 2026-11-14
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

Built 2026-11-06 (ADR 127): the player's quality menu and the cast's "beyond the TV" line use `QualityLabel`; the
download chooser says "The video, 1080p · VP9" when the resolver still holds the ladder; yt-dlp downloads skip codecs
the phone cannot decode and software AV1 (`VideoCodecSupport.codecsToAvoid` into `EngineDownloadStrategy`).

Tests owed *(written in part 2026-11-14: see "Tests written 2026-11-14" below for what is done and what is still owed)*: selector cases in `EngineDownloadStrategyTest` (no avoid list unchanged, AV1 avoided, language and cap
variants), `codecsToAvoid` for a hardware-AV1, a software-AV1 and a no-VP9 phone, label cases in the quality ladder
tests (`VideoQuality.label`), `DownloadChooser.qualityFor` (cap, no ladder). Device check: a download on a phone without
hardware AV1 never lands AV1 (read the `[download] ... yt-dlp selector` line, then the file's codec).

Consistency sweep 2026-11-06: the episode picker's quality chips (queue groups, torrent packs, books) take the same
`QualityLabel` as the single-item question, through one `videoChoiceText`; the codec shows when the resolver holds a
ladder for one of the first five items, else the bare height (codec unknown).

## Tests written 2026-11-14

`TheQualityMenuNamesTheCodecTest` (both ladders name the winning codec at each height, an unknown or missing codec is the bare height, the id stays the height, live keeps its label), `QualityLabelTest`, `DownloadChooserQualityForTest` (cap, no ladder), `ADownloadSkipsCodecsThePhoneCannotPlayTest` (the yt-dlp selector for every mix of avoid list, cap, language and sound-only; the avoid list is asked for the capped height and never for sound). `CodecsToAvoidTest` already covered the avoid list itself. Owed: `videoChoiceText` (a composable) and a download on a phone without hardware AV1 read from the `[download] ... yt-dlp selector` line.
