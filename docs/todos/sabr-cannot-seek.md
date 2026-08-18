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

Four responses of about 1KB each, carrying control parts and **no usable media**: `segments=0[]`, and the
single header arrived as `id0:itag258:seq?:at-1` — no sequence number, no start. So YouTube declines a
cold mid-stream open in this request shape.

⚠️ Read `discarded` carefully here. It is `response.size - added`, so it counts **protocol overhead as
well as** already-passed media — "100% wasted" on a control-only response is expected arithmetic, not a
second symptom. Judge whether media arrived from `segments=` and the header list, not from that
percentage.

`SabrServesWhatWeChooseTest.sabrCanBeOpenedPartWayThrough` asserts the half we own — that the request
aims near the right time — and *reports* whether YouTube served it, so the build does not go red for a
capability nobody has built yet. If it ever starts serving, that printed line says so and tells the
reader to revisit the 10s window.

## Ruled out: session continuity (2026-08-18)

The leading theory was that a jump is only permitted inside a conversation YouTube already recognises —
a cold stream having no playback cookie or prior buffered ranges. Tested by reading sequentially from the
start and only then jumping:

```
warmed the conversation with 1892KB over 4 reads, now at 1938378B
then jumped to 15383805B and got 0KB
fetches=8 served=1938378B segments=4[1, 2, 3, 4] mediaTime=407499ms
```

The warm jump asked for the right time (407499ms) and was still served no media. **Session continuity is
not the missing piece.**

⚠️ **The first version of that probe was an invalid instrument and nearly produced this same conclusion
without earning it.** `aimAtByte` originally fired only on a *cold* open, so after warming it skipped —
and the "jump" asked for 130005ms, the position sequential reading had already reached, rather than
407499ms. It measured nothing, and its output looked exactly like a real negative result. The fix (aim on
any *discontinuity*, judged from `handedThrough`, since `contiguousFrom` consumes what it returns) is
both the correct behaviour and what made the probe real. **A negative result from an instrument you have
not verified is worth nothing** — the second run asking for 407499ms is what makes this finding valid.

## Where to look next

1. **NOT the `SABR_SEEK` part** — correcting a lead written earlier in the same session, and then the
   correction itself. UMP is the **server→client** framing, so `SABR_SEEK` is YouTube telling *us* it has
   repositioned; it is not something a client can send. And the id was wrong too: the hand-written table
   called 43 `SABR_SEEK`, but per `UMPPartId` 43 is `SABR_REDIRECT` and `SABR_SEEK` is **45**. The table
   has since been regenerated from the proto — 10 of its 16 entries were wrong. A seek must therefore be expressed in the REQUEST, in
   `VideoPlaybackAbrRequest`/`ClientAbrState` fields we do not populate yet. Worth diffing our request
   against `LuanRT/googlevideo`'s protos for what a real player sends alongside `player_time_ms`.

   I also flagged ignoring `SABR_SEEK` as a possible mis-attribution bug of its own. **Checked, and it
   is not one:** `storeMedia` places bytes using the `MEDIA_HEADER`'s own `startBytes`
   (`writeAt[headerId] = known.startBytes`), never the offset we asked for. The server tells us where
   each run belongs and we honour that, so a silent reposition cannot land bytes in the wrong place.
   Handling `SABR_SEEK` may still be needed to *notice* a reposition; it is not a correctness risk.
2. **A byte↔time reconciliation layer.** Even when the server serves the right media time, the run it
   returns starts at whatever byte that time maps to — not necessarily the byte ExoPlayer asked for. A
   ratio estimate cannot be byte-exact, so `SabrDataSource` would need to present a virtual byte space
   and reconcile, rather than requiring an exact match.
