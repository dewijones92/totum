---
title: SABR cannot play a live stream without its initialization segment
kind: todo
area: playback
priority: medium
status: open — measured and understood; the parts needed are already on the wire
updated: 2026-08-19
---

# SABR cannot play a live stream without its initialization segment

SABR **fetches** a live stream without complaint. It cannot **play** one, and the reason is small and
specific: a live stream joins mid-broadcast, so its media arrives with no initialization segment, and
`ProgressiveMediaSource` has no `moov` to parse it with.

## Measured, 2026-08-19, on `totum-api35` against a real live stream

The bytes are served:

```
[sabr] fetch #1 itag 140 at 0ms -> 82466B response, 82153B kept
[sabr] fetch #1 itag 135 at 0ms -> 902900B response, 820060B kept
```

and the player never becomes ready. The run headers say why — a VOD gets an init segment first, a live
stream gets none at all:

```
VOD  : run 0 itag=135 init=true  startBytes=0    length=2249   <- the moov
       run 1 itag=135 init=false startBytes=2249 seq=1
LIVE : run 1 itag=135 init=false startBytes=0    seq=2770558   <- nothing to parse it with
```

## Why this is buildable rather than impossible

The init data **is on the wire**. Logging the UMP parts `SabrStream` discards showed a live response
carrying exactly what is missing:

```
ignored parts: STREAM_PROTECTION_STATUS(58), SELECTABLE_FORMATS(51), LIVE_METADATA(31),
               FORMAT_INITIALIZATION_METADATA(42), SABR_SEEK(45), NEXT_REQUEST_POLICY(35),
               START_BW_SAMPLING_HINT(49), MEDIA_END(22)
```

`SabrStream.absorb` handles `MEDIA_HEADER` and `MEDIA` and ignores everything else, so
`FORMAT_INITIALIZATION_METADATA(42)` — and `LIVE_METADATA(31)`, which is how a live edge and window are
described — go in the bin.

## What was done instead, and why

`SabrResolve.prepare` now refuses a live stream **by name**, and live falls back to extraction, which
plays it. That is not a workaround for its own sake: live used to be refused by ACCIDENT, through a
`lastModified != null` gate aimed at identifying formats, and relaxing that gate (correct in itself —
SABR fetches such formats fine) would otherwise have handed live to a path that fetches and never plays.
An accidental refusal that stops being accidental is how a working feature disappears quietly.

## The work, if it is picked up

1. Parse `FORMAT_INITIALIZATION_METADATA(42)` and hold the init bytes for the chosen itag.
2. Serve those bytes before the first media bytes, so the extractor sees a complete stream.
3. Parse `LIVE_METADATA(31)` for the live edge, and stop presenting a live stream as a bounded file.
4. Then delete the refusal above and let `SabrPlaysAcrossVideoTypesTest`'s live fixture assert instead of report.

Related: `docs/todos/sabr-cannot-seek.md` (`SABR_SEEK(45)` is ignored too, and is likely part of the same
gap), `docs/features/sabr-streaming.md`.
