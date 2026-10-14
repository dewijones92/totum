---
title: YouTube refuses subtitles with 429 for minutes at a time; the app hammered it and said nothing
kind: todo
status: a refusal now loads an empty subtitle (2026-10-14, evening); the on-screen message and a second source are open
area: playback
priority: high
requested: 2026-10-13
updated: 2026-10-14
---

# Subtitles refused with 429

Report `20261013T060655-3da60570` (0.1.605, a57f66d, Pixel 7), Dewi: *"sub titles not working??"*.

## What the report shows

- In listen mode the route is audio-only (then the downloaded audio file), so `[subtitles] …: none offered`
  is expected: no picture, nothing to show them on. After **Watch** at 06:59:14, all 8 tracks were offered.
- Picking English at 06:59:46: every `/api/timedtext` request answered **429 Too Many Requests**, retried
  on Media3's 1–5 s back-off, 8+ times per pick and again on every language change (42 refusals by 07:06).
  The screen never said why.
- Report #45 (0.1.602) also logged `timedtext` 429s, so it recurs.

## Reproduced off the phone (2026-10-14, the laptop on the same home connection)

`yt-dlp --write-auto-subs --sub-langs en` for the same video: **HTTP 429** with `web_embedded` and
`android_vr`; `ios` and `mweb` need a PO token; `tv` (anonymous) lists no captions; the signed-in TV
`/player` (the app's token) also returns no caption tracks. yt-dlp warns the caption download "specified
impersonation" (a browser TLS fingerprint) which it could not provide. So YouTube is refusing the caption
fetch itself, not the app's rate; CI's `SubtitlesArriveAndRenderTest` (same home tunnel) passed on 13 Oct
afternoon, so the refusal comes and goes.

## Done (2026-10-14)

Morning: `subtitleRefusalDelayMs` made a 429 wait 30 s, 60 s, up to 120 s before asking again.

Evening, reports `20261014T180612` and `20261014T181056` (*"English subs not working??"*, then *"it just
buffering???"*): that back-off **froze the video**. While a side-loaded subtitle waits to retry, its period's
next load position stays at the start point. Media3's `CompositeSequenceableLoader` only asks the loader at the
lowest next load position, or one already behind playback, to continue; a progressive load pauses every 1 MB
and waits to be asked. So the picture and sound stopped refilling. On a loopback server serving a 429 for the
captions, a WAV stopped at exactly 6.0 s (1 MB) and never moved in 25 s.

Now `RefusedSubtitlesLoadEmpty` (outermost data source, in `playbackSources`) answers a 429 from
`/api/timedtext` with an empty subtitle file in the asked-for format, so the subtitle load finishes and the
item plays on with no captions. Logged as `[subtitles] YouTube refused the … subtitles with 429 …; showing none
for this item`, counted in `subtitles.refused429`. The back-off is gone. Tests:
`ARefusedSubtitleDoesNotStarveThePlaybackTest` (device; red at 6000 ms before, 24.8 s and no rebuffer after),
`SubtitleRefusalTest`.

Separately, the refused track in those reports was a translation that should never have been offered: on a
dubbed video `en` was Arabic translated to English, and the real `en-orig` (200 from the same network) lost
to it. See `docs/features/subtitles.md`.

## Open

- **Say it on screen**: the subtitle menu should show "YouTube is refusing subtitles right now" while a
  refusal stands. Needs a subtitle status carried from the playback layer to `VideoSettings`.
- **A second source**: browser-impersonated fetching (curl_cffi is not on Android), the WEB
  `get_transcript` endpoint, or subtitles saved with downloads so a later refusal does not matter.
