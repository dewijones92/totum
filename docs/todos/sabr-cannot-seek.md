---
title: SABR cannot be opened part-way through
status: partly-done
updated: 2026-08-18
---

# SABR cannot be opened part-way through

The single limitation keeping SABR out of the app's ordinary playback path. It is offered only as a
rescue, within `SABR_START_WINDOW_MS` (10s) of an item's start, and the `sabrPlayback` setting stays off
by default because a fast start that cannot scrub is a poor trade.

## Fixed: the units bug (2026-08-18)

ExoPlayer opens a track at a **byte offset**; a SABR request asks for a **media time**. Nothing
translated between them, so a resume that opened a video track ~41MB in still asked for
`player_time_ms = 0`, received the start of the file, discarded every byte as already-passed, and the
video track died at 16% while the audio played on — a video with no picture (measured 2026-07-31).

`SabrStream.aimAtByte` now converts, reusing the `HeldSegments.timeOfByte` ratio that
`advanceClaimedTime` already relied on. Only on a cold open — never during sequential reading, where
following the bytes actually held is more truthful, and where re-estimating could move the claim
backwards and be read by the server as a seek (which re-sends everything: the 52%-wasted-bytes problem
that buffered ranges exist to prevent). Covered by `OpeningAtAnOffsetAsksForThatTimeTest`.

## Still blocked: YouTube serves no media for a cold jump

The aim is now right and it is **not enough**. Live, opening halfway into a 30MB audio stream:

```
mediaTime=407499ms   ← correct, halfway through an ~815s stream
fetches=4  served=0B  discarded=8152B (100% wasted)  segments=0[]
```

Four responses of about 1KB each, carrying control parts and **no media at all**. So YouTube declines a
cold mid-stream open in this request shape.

`SabrServesWhatWeChooseTest.sabrCanBeOpenedPartWayThrough` asserts the half we own — that the request
aims near the right time — and *reports* whether YouTube served it, so the build does not go red for a
capability nobody has built yet. If it ever starts serving, that printed line says so and tells the
reader to revisit the 10s window.

## Where to look next

1. **The `SABR_SEEK` part.** It appears in real responses (`UmpPart.SABR_SEEK`) and is presumably how a
   seek is meant to be expressed. Nothing sends it.
2. **Session continuity.** A cold stream has no `playbackCookie` or prior `buffered_ranges`; a jump may
   only be permitted within an established conversation. Testable: play from 0 for a few fetches, THEN
   jump, and see whether the same offset is served.
3. **A byte↔time reconciliation layer.** Even when the server serves the right media time, the run it
   returns starts at whatever byte that time maps to — not necessarily the byte ExoPlayer asked for. A
   ratio estimate cannot be byte-exact, so `SabrDataSource` would need to present a virtual byte space
   and reconcile, rather than requiring an exact match.
