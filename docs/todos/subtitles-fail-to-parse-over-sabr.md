---
title: Subtitles fail to parse when playback goes over SABR
kind: todo
status: evidenced and instrumented; cause not yet known
area: playback
updated: 2026-09-20
---

# Six identical subtitle failures, only on the SABR route

CI runs 35515542462 and 35518233998 (2026-09-20) each carry **six** of these, and both times all
six fall inside one test — `AnHourLongItemDoesNotRebufferTest.anHourLongVideoPlaysOnWithoutRebuffering`,
the case that turns SABR on:

```
[load] track--1 failed (canceled=true) after 2196ms — www.youtube.com /api/timedtext
       — androidx.media3.common.ParserException: SubtitleParser failed.
         {contentIsMalformed=true, dataType=1}
```

`contentIsMalformed` means the bytes did not match the mime type the app declared for that track.
The app supplies both: `SubtitleTrack.format.mimeType` in
`Media3PlaybackController.toSubtitleConfiguration`, and the URL — whose `fmt` parameter is what
YouTube actually answers.

## Why it was not noticed

`SubtitlesArriveAndRenderTest` passes, in the same runs, minutes earlier. It never touches SABR
(no `sabrPlayback` in the file), so the two routes have never been compared. This is the
[[covered-components-unconnected-edge]] shape: the feature is tested, the route is not.

It is also not fatal — playback continues without captions — which is why it has been sitting in
every report as six lines nobody read.

## What is known, and what is not

- **Known:** it happens only under SABR, repeatably, six times per affected play, on a video whose
  resolution reports `2 subtitle tracks` over SABR against `8 subtitle tracks` on the ordinary
  route for the same video. So SABR's player response offers a different, smaller set.
- **Not known:** whether the declared mime is wrong, the `fmt` is wrong, or the response is an
  error body. Nothing logged either.

## Instrumented, 2026-09-20

`Media3PlaybackController.describeSubtitles` now logs, per play:

```
subtitles <id>: 2 track(s) — en/English (original) declared=application/ttml+xml asks=ttml | …
```

The next CI run with SABR playback should settle it in one line. **Do not guess at a fix before
reading that** — the plausible causes (wrong mime, wrong `fmt`, an error body) have different fixes
and the report will say which.

## When fixing it

Give `SubtitlesArriveAndRenderTest` a SABR case, or the same gap reopens the next time the routes
diverge.
