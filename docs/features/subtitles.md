---
title: Subtitles and captions
kind: feature
status: shipped
area: playback
updated: 2026-10-14
---

# Subtitles

**Ask (Dewi):** "later lets also do subtitles/captions things".

Caption tracks are extracted with the video, offered in a menu on the video overlay, and
rendered over the picture. Off by default; auto-generated tracks are labelled as such.

## What it's built on — and what it deliberately doesn't do

**Media3 decodes the text track; we don't.** Cues are read straight off the player via
`Player.Listener.onCues`. Parsing WebVTT ourselves would be a second implementation of
something the player already does, free to disagree with it.

The data was already there: the Python bridge returns yt-dlp's whole `info` dict, so
`subtitles` and `automatic_captions` were crossing into Kotlin and being ignored. This
feature is mostly *reading what was already arriving*.

## The seam

`SubtitleTrack` and `SubtitleFormat` live in **`:lib:common`**, beside `HttpUrl` and
`Page` — both the extraction engine and the playback layer need them, and podcasts have
`podcast:transcript` in RSS, so the same seam will serve both pillars rather than needing a
twin later.

| Piece | Where |
|---|---|
| `SubtitleTrack`, `SubtitleFormat` | `:lib:common` |
| Parsing + all the filtering judgement | `:lib:ytdlp-chaquopy` / `SubtitleJson.kt` |
| `MediaMetadata.subtitles` → `VideoResolver.Resolved.subtitles` | `:lib:ytdlp`, `:app` |
| `play(…, subtitles)`, `setSubtitleLanguage(…)` | `:core:playback` |
| Menu + cue rendering | `:app` / `VideoSettings.kt`, `SubtitleCues.kt` |

## Three filtering decisions

All in `SubtitleJson.kt`, all with tests:

1. **Only formats the player can decode.** YouTube offers `srv1`/`srv2`/`srv3`/`json3`
   alongside `vtt`; `SubtitleFormat.fromExtension` returns null for those and the track is
   dropped at the boundary. A track we can't render is *worse* than no track — it appears
   in the menu and then silently shows nothing.
2. **Auto-captions are limited to a small language set.** YouTube advertises its machine
   transcription translated into ~100 languages. A hundred-item menu is not a feature.
   Author-provided tracks are never filtered by language, because someone chose to write
   those.
3. **Collapse auto-captions by *base* language, and never where an author wrote it.**

That third rule came from real data, not from thinking about it. The first build listed
both "English" *and* "English (Original) (auto)" for a Veritasium video, because YouTube
keys the original-language ASR as **`en-orig`** — which an exact-code comparison sees as a
different language. Same for "Portuguese" and "Portuguese (Portugal)" machine versions of
one thing. Hence `SubtitleTrack.baseLanguage`, and three tests using those exact shapes.

Within a language a `-orig` track wins (2026-10-14, report `20261014T180612`, *"English subs not
working??"*). On a dubbed video YouTube lists one transcription per audio track, Arabic first, and
the bare `en` is that Arabic transcription machine-translated to English; the real English one comes
later as `en-orig`. Keeping the first per language offered the translation, which YouTube answered
with 429 while `en-orig` answered 200 from the same network. Test: `a transcription in the language
wins over a translation into it`.

## Playback details worth knowing

- Tracks are **side-loaded** as `MediaItem.SubtitleConfiguration`. There was a trap here
  that turned out fine: the service wraps sources in a `MergingMediaSource` to pair
  video-only with audio-only for higher qualities, which could have dropped the text
  tracks — but it delegates to `DefaultMediaSourceFactory`, which attaches subtitle
  configurations itself, so captions survive the merge.
- **Off means off.** `setSubtitleLanguage(null)` disables the whole text track type rather
  than just clearing the preferred language, because leaving it enabled lets the player
  fall back to a default track — so "off" would quietly still show something.
- A **language**, not a track index: an index goes stale the moment the item changes, which
  is exactly when a "keep subtitles on" preference would need to survive.
- Cues sit above the picture and below the controls, and lift clear of the seek bar while
  the controls show.

## Tests

- `lib/common/.../SubtitleTrackTest.kt` — MIME types, extension recognition (incl. casing),
  refusal of YouTube's undecodable formats, blank-field rejection.
- `lib/ytdlp-chaquopy/.../SubtitleParsingTest.kt` — 13 tests over the filtering: formats,
  languages, one-per-language, author-beats-auto, the `en-orig` and `pt-PT` real-world
  cases, author variants kept, missing URL skipped.

## Verified on-device

On a real Veritasium video: the **Subtitles** button appears in the overlay, the menu lists
the true tracks ("English" author-provided, plus auto-generated Dutch/French/German/Italian/
Portuguese/Spanish), selecting English logged `[subtitles] language -> en`, and the cue
**"(light music)"** rendered over the video.

## Not done yet

- **Podcast transcripts** (`podcast:transcript`). The seam is deliberately pillar-neutral
  so this is a parser plus a feed field, not a redesign.
- **Remembering the choice** across items — currently captions default to off each time.
- Styling options (size, background). One legible default for now.

## What the trail now says (2026-10-14)

Report `20261014T195413` (0.1.614, *"subtitles not working??"*): English (Original) was chosen and nothing more
was logged, because a load that worked and a caption that showed said nothing. `SubtitleTrail` now logs the text
track selected (and how many were offered), each caption file loaded (language, bytes, time) and the first caption
on screen. `AnOriginalLanguageTrackShowsCuesTest` (device) shows an `en-orig` track rendering cues exactly as `en`
does, and the real `en-orig` TTML for that video parses, so the phone's cause is not yet known.
