---
title: Subtitles fail to parse when playback goes over SABR
kind: todo
status: evidenced and instrumented; cause not yet known
area: playback
updated: 2026-09-20
---

# Six identical subtitle failures, only on the SABR route

CI runs 35515542462 and 35518233998 (2026-09-20) each carry **two** of these — one per subtitle
track, which matches the `2 subtitle tracks` those plays resolve. (An earlier version of this file
said six. That was a line count: each failure logs three lines, and I counted the echoes.) Both
times both of them fall inside one test — `AnHourLongItemDoesNotRebufferTest.anHourLongVideoPlaysOnWithoutRebuffering`,
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

## Run 35520676271 is NOT a healthy control

It carries zero `SubtitleParser failed`, and an earlier version of this file called that difference
"unexplained" and treated it as a control. It is neither. Its subtitle loads failed **earlier and
differently**:

```
[load] track--1 failed after  9281ms — /api/timedtext — HttpDataSourceException: SocketTimeoutException
[load] track--1 failed after 10060ms — /api/timedtext — HttpDataSourceException: SocketTimeoutException
```

against 1.6-2.2s and a parse failure in the other two. The bytes never reached the parser, so the
parser could not fail. Subtitles are broken in all three runs; only the stage differs.

Recorded at length because calling that a control was the exact mistake the control-case rule in
`../tests/_index.md` exists to prevent, made in the commit that introduced the rule.

## Why it was not noticed

`SubtitlesArriveAndRenderTest` passes, in the same runs, minutes earlier. It never touches SABR
(no `sabrPlayback` in the file), so the two routes have never been compared. This is the
[[covered-components-unconnected-edge]] shape: the feature is tested, the route is not.

It is also not fatal — playback continues without captions — which is why it has been sitting in
every report as lines nobody read.

## What is known, and what is not

- **Known:** it happens only under SABR, repeatably, twice per affected play, on a video whose
  resolution reports `2 subtitle tracks` over SABR against `8 subtitle tracks` on the ordinary
  route for the same video. So SABR's player response offers a different, smaller set.
- **Not known:** whether the declared mime is wrong, the `fmt` is wrong, or the response is an
  error body. Nothing logged either.

## Instrumented, 2026-09-20

`Media3PlaybackController.describeSubtitles` now logs, per play:

```
subtitles <id>: 2 track(s) — en/English (original) declared=text/vtt asks=vtt | …
```

**`declared=` is a constant.** `PlayerResponseParser.captionTracks()` hardcodes `SubtitleFormat.VTT`
for every InnerTube-derived track, and that is the only source on the SABR route — so the mime the
app claims cannot vary **on this route**. The first version of this file showed a `ttml` example,
which the yt-dlp route genuinely can emit (`SubtitleFormat.TTML` exists and `fromExtension` returns
it) but the SABR route cannot — so the example implied a variability that does not exist where the
bug is. What the line genuinely discriminates is `asks=`: the URL is built by
`base.replace("fmt=srv3", "fmt=vtt")`, which **silently no-ops** when the response's `baseUrl` does
not carry `fmt=srv3`. So `asks=srv3` or `asks=no fmt` against `declared=text/vtt` is exactly the bug
shape, and it is the likeliest of the three hypotheses.

The third — an error body at a URL that looks right — is **not** captured by this line. If `asks=vtt`
comes back clean, that is where to look next, and it needs its own instrumentation.

**Do not guess at a fix before reading a run.**

## When fixing it

Give `SubtitlesArriveAndRenderTest` a SABR case, or the same gap reopens the next time the routes
diverge.
