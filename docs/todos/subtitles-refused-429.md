---
title: YouTube refuses subtitles with 429 for minutes at a time; the app hammered it and said nothing
kind: todo
status: back-off shipped 2026-10-14; the user-facing message and a second caption source are open
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

`subtitleRefusalDelayMs` in `DoNotRetryWhatSabrHasGivenUpOn`: a 429 on `/api/timedtext` waits 30 s, then
60 s, up to 120 s, instead of every second; logged as `[subtitles] YouTube refused the subtitles with 429 …`
and counted in `subtitles.refused429`. Not "never retry": a subtitle period is lazy and its own source, and
a fatal error there would end playback (see the 0.1.597 rewind notes, `UnaskedRewindGuard`).
`SubtitleRefusalTest`.

## Open

- **Say it on screen**: the subtitle menu should show "YouTube is refusing subtitles right now" while a
  refusal stands. Needs a subtitle status carried from the playback layer to `VideoSettings`.
- **A second source**: browser-impersonated fetching (curl_cffi is not on Android), the WEB
  `get_transcript` endpoint, or subtitles saved with downloads so a later refusal does not matter.
