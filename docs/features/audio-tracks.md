---
title: Audio tracks and language
kind: feature
status: shipped
area: playback
updated: 2026-08-19
---

# The right language, and a way to say otherwise

YouTube publishes automatic dubs alongside the uploader's own audio, and it publishes them
as **ordinary formats**. A video with an English original and a German auto-dub is a video
with two 1080p streams. Nothing about them says "this is the one you meant" unless you look.

Totum was not looking. Report 0.1.373 (2026-08-09) is Dewi watching a Black Hat conference
talk — given in English — in automatic German. The URL the app chose says so itself:

```
…/sgoap/clen=36375359;dur=2247.575;itag=140;xtags=acont=dubbed-auto:lang=de-DE/…
```

## Why it happened

Not one bug: **five places answered "which stream should I play?" and only one of them
looked at language.**

| Picker | Rule it used | Verdict |
|---|---|---|
| `bestAudioFormat` (audio-only / Listen) | language, then size | the one that was fixed, in July |
| `bestPlayableFormat` (the item's stream URL) | tallest | language-blind |
| `videoQualities` (the quality ladder) | first muxed at each height | **language-blind, and the one that chose** |
| `StreamingData.videoQualities` (the `/player` fallback) | highest bitrate | fixed in two halves — see below |
| `SabrResolve.bestAudio` (experimental, off) | highest bitrate | language-blind |

The resolve log made it worse rather than better. It printed `audio en-US (preference 10,
offered en-US)` — perfectly true of the audio-**only** pick, and completely irrelevant to the
muxed stream that actually played. A line that is true and answers a different question is
harder to work with than no line.

## The seam

One type — `AudioTrackTag` in `:lib:common` — answers *"what language is this stream's sound,
and is it a dub?"*, and one comparator, `audioLanguagePreference(wanted)`, orders by it. Every
picker above now sorts through that comparator; none of them holds its own opinion.

The tag is read from two sources because neither is always present:

- the **extractor's fields** (`language`, `languagePreference` — yt-dlp scores the original 10);
- the **URL**, via `xtags=acont=…:lang=…`, which is what saved this. On the phone yt-dlp has no
  JavaScript runtime, falls back to YouTube's HLS manifest, and labels those variants
  inconsistently. The URL always labels itself.

### What "the right language" means

Ordered: **a language you asked for**, then **the uploader's own track**, then anything.
`wanted` is the device's languages (`en` on Dewi's phone) unless you have chosen a track by
hand, in which case it is exactly that one. An **unstated** language deliberately outranks a
known-unwanted one — most videos label nothing at all, and treating silence as "wrong" would
reject the only track there is.

So an English video plays English; a German video with no English track plays German rather
than nothing; and an English auto-dub of a German video is chosen over the German original,
because that is what "default to English" means.

### The ladder belongs to the track

The picker fix alone changed nothing, which the tests caught before the phone did. The quality
ladder still offered 1080p — which existed only as the German dub — and the auto-pick takes the
tallest. So a height whose sound is worse than the best available on that video is **dropped**,
and comes back the moment you select that language. The ladder is the ladder for the track you
are listening to.

That rule held on ONE ladder for nine days. `StreamingData.videoQualities` — the ladder built from
a `/player` response, which is what serves you whenever yt-dlp comes back degraded, i.e. the whole
SABR-stripped present — needed two separate fixes to catch up: `wanted` was never passed to it at
all (2026-08-18), and even with the language in hand it preferred a muxed stream on being *muxed*,
never comparing its sound against the audio-only tracks (2026-08-19). So asking for German where
German existed only as an audio-only track served the English muxed — 0.1.373's own bug, arriving
by the other route, months after the route everyone was looking at was fixed.

The two ladders stay separate on purpose: a `MediaFormat` knows codecs and whether it has audio, a
`PlayableFormat` carries only a mime type, so one function would have two disjoint halves. That is
precisely why the *rule* has to be asserted against both — a recorded, reasoned duplication still
duplicates the rules that live inside it. `ASecondOpinionRungKeepsYourTrackTest` now pins all three
cases (merge instead, drop the height, keep the muxed one when its language is right).

## The menu

`VideoSettingsControls` gains a translate icon beside speed, quality and subtitles, listing
one row per language: *English (original)*, *German (dubbed)*. The suffix is not decoration —
two rows both reading "English" would be unusable.

Shown only when there is more than one track, the same rule the quality menu follows.

Choosing one re-picks **every** stream for that language off the cached metadata: no second
extraction, and the language cannot be left half-applied (on HLS it is baked into the muxed
variant, so swapping only the merge partner would leave the picture still speaking the old
one). Playback replays from where it was; Listen mode stays Listen mode.

The choice is per-video, like quality — not a remembered preference. The remembered preference
is the device's language, which is what the default already follows.

## What a report will say now

```
[resolve]  87DyyMV0kCY in 11731ms for play — 5 qualities, 8 subtitle tracks, audioOnly=true,
           audio en-US (English (original)) via format 96-en;
           offered English (original)/German (dubbed); wanted en
[playback] 87DyyMV0kCY stream 1080p avc1.640028 audio English (original) (merged);
           2 track(s) offered
[playback] 87DyyMV0kCY audio track -> de-DE
```

The second line is the one that was missing. The resolve line describes a decision; that one
describes **the stream handed to the player**, which is the thing you can be wrong about.

## Tests

| Level | Where | What |
|---|---|---|
| unit | `lib/common/…/AudioTrackTagTest` | xtags parsing (the real URL from 0.1.373), ordering, labels |
| unit | `lib/ytdlp/…/AudioLanguageSelectionTest` | every picker prefers the right language; the tallest still wins within it |
| unit | `app/…/VideoQualityTest` | a dub-only height is dropped, and returns when asked for |
| unit | `app/…/AudioTrackSelectionTest` | end to end through the launcher, including Listen and the re-use of one extraction |
| instrumented | `app/…/AudioTrackMenuTest` | the menu appears where there is a choice and asks for the language tapped |

The six `AudioLanguageSelectionTest` cases were run against the old picker first and all six
failed, which is what makes them a guard rather than a description.

## Not covered

- **`SabrResolve`** now sorts by the same comparator, but SABR is off by default and unverified
  against a dubbed video.
- **Downloads** take the same `bestAudioFormat`, so they follow — untested against a dub.
- Nobody has yet heard this work on the phone. The `[playback] … audio …` line in the next
  report is what will settle it.
