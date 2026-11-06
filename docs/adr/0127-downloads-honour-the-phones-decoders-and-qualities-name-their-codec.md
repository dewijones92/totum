---
title: Downloads honour the phone's decoders, and every quality names its codec
kind: adr
status: accepted
updated: 2026-11-06
---

# 127. Downloads honour the phone's decoders, and every quality names its codec

- Status: Accepted (extends [ADR 122](0122-a-cast-sends-the-best-rung-and-steps-down-itself.md), whose `QualityLabel` it spreads)
- Date: 2026-11-06

## Context

Dewi, 2026-11-05: show codecs with qualities, prefer AV1, and no setting for it. The ladder the player offers was already
filtered and ranked by the device's decoders (`VideoCodecSupport`), but its menu said "1080p" whatever the codec, and the
yt-dlp download path (`bv*[height<=H]+ba/...`) knew nothing about decoders: yt-dlp's own order puts AV1 first at a
height, so a phone without a hardware AV1 decoder downloaded a file it could only play in software, or not smoothly.

## Decision

- **One label.** `QualityLabel(height, family)` ("1080p · AV1") is built wherever a quality is named: the player's quality
  menu (both ladders: yt-dlp formats in `VideoQuality.kt`, the player response in `PlayerStreams.kt`), the cast strip and
  the cast's "beyond the TV" line. The seek-cap setting stays a plain height because it is a ceiling, not a stream.
- **One rule, no setting.** Hardware AV1, then VP9, HEVC, H.264; never software AV1. The ranking is
  `VideoCodecSupport.preference`, taking the target profile (the phone's decoders, or `CastScreen` while casting).
- **Downloads are for the phone, not the TV.** `EngineDownloadStrategy` takes a `videoCodecsToAvoid(height)` seam and adds
  `[vcodec!^=?prefix]` filters to every video selector. `VideoCodecSupport.codecsToAvoid` answers: every family the
  phone cannot decode at the height, and AV1 unless its decoder is hardware. yt-dlp's own order (tallest first, then
  AV1, VP9, HEVC, H.264) decides among the rest, so a hardware-AV1 phone still gets AV1 and the height is never traded
  for a codec. The plain `/b` fallback stays last so a video with only AV1 video-only streams still downloads, from its
  muxed stream. Downloads use the phone's own support (`PlatformVideoCodecSupport`), not the cast-aware one the resolver
  holds, so casting does not change what a download picks; the Pi's service passes the asking phone's decoders
  (`PhoneCodecs`) through its resolver.
- **The download chooser names the codec where the ladder is known.** If the resolver still holds this video's ladder
  for the phone (`VideoResolver.knownQualities`), "The video, 1080p" becomes "The video, 1080p · VP9". The held ladder's
  pick can differ from yt-dlp's (the ladder prefers a durable URL, then the ranking), so this is the codec the phone
  would stream, which the download follows in the common case. Without a held ladder the label stays plain.

## Consequences

- A phone with no AV1 hardware never downloads AV1 where another codec exists; the selector is logged per download
  (`[download] <id> yt-dlp selector ...`).
- Software VP9 is not demoted behind hardware H.264 in a download (the ladder does that for playback). That needs a
  yt-dlp format sort and bridge change; left for when a phone shows it.
- Tests owed: `EngineDownloadStrategyTest` selector cases, `codecsToAvoid` against `PhoneCodecs`, `VideoQualityTest`
  label cases, `DownloadChooser.qualityFor`.
