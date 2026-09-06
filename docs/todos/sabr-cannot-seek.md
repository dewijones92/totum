---
title: SABR cannot be opened part-way through
status: open (the warm-jump lead is reopened)
updated: 2026-09-06
---

# SABR cannot be opened part-way through

The limitation keeping SABR out of the app's ordinary playback path. It is offered only as a rescue,
within `SABR_START_WINDOW_MS` (10s) of an item's start, and the `sabrPlayback` setting stays off by
default because a fast start that cannot scrub is a poor trade.

## Two failures, and only one of them is YouTube's

They were written up as one thing, which is how a lead got crossed off that should not have been.

| | Failure | Evidence | State |
|---|---|---|---|
| **THEIRS** | a COLD mid-stream open is answered with no media | wire-measured: ~1KB responses, `segments=0[]` | proven |
| **OURS** | a granted seek could not be consumed even if it arrived | code: exact-key match + untrimmed discard | proven |

Both have to be fixed for seeking to work, and until 2026-08-20 the second was invisible behind the
first.

## Fixed: the units bug (2026-08-18)

ExoPlayer opens a track at a **byte offset**; a SABR request asks for a **media time**. Nothing
translated between them, so a resume that opened a video track ~41MB in still asked for
`player_time_ms = 0`, received the start of the file, discarded every byte as already-passed, and the
video track died at 16% while the audio played on — a video with no picture (measured 2026-07-31).

`SabrStream.aimAtByte` now converts, reusing the `HeldSegments.timeOfByte` ratio that
`advanceClaimedTime` already relied on. Only on a **discontinuity** — never during sequential reading,
where following the bytes actually held is more truthful, and where re-estimating could move the claim
backwards and be read by the server as a seek (which re-sends everything: the 52%-wasted-bytes problem
that buffered ranges exist to prevent). Covered by `OpeningAtAnOffsetAsksForThatTimeTest`, and at byte
0 — a rewind on a warm stream — by `AWarmStreamCanRewindToTheStartTest`.

## THEIRS: no media for a cold jump — wire-measured

Live, opening halfway into a 30MB audio stream:

```
mediaTime=407499ms   ← correct, halfway through an ~815s stream
fetches=4  served=0B  discarded=8152B (100% wasted)  segments=0[]
```

Four responses of about 1KB each, carrying control parts and **no usable media**: `segments=0[]`, and
the single header arrived as `id0:itag258:seq?:at-1` — no sequence number, no start.

**This one is sound because it is measured on the wire, not at the reader.**
`SabrServesWhatWeChooseTest.sabrCanBeOpenedPartWayThrough` wraps the transport
(`SabrTransport { url, body -> transport.post(url, body).also { sizes += it.size } }`) and prints the
response sizes, so the ~1KB figures are HTTP response bodies. No defect anywhere in our reader can
shrink a response to 1KB. Nothing was served.

⚠️ Read `discarded` carefully here. It is `response.size - added`, so it counts **protocol overhead as
well as** already-passed media — "100% wasted" on a control-only response is expected arithmetic, not a
second symptom. Judge whether media arrived from `segments=` and the header list, not from that
percentage.

⚠️ **It proves no media came back; it does not prove that SEEKING is what was refused.** The target was
byte 15383805, and [sabr-stops-at-one-megabyte.md](sabr-stops-at-one-megabyte.md) measured every audio
stream in an eighteen-stream run ending between 968840B and 990078B, with the server answering past
that point with the initialization segment and nothing else. A cold open at 15MB is on the far side of
that ceiling, so an attestation refusal and a seek refusal produce the same observation. Distinguishing
them needs a cold open at an offset **inside** the first megabyte, which nothing has run.

## OURS: we cannot consume a granted seek

Independent of what YouTube serves, `SabrStream` could not use a mid-stream answer:

- **`read(from)` needs `chunks[from]` to exist at the exact offset.** `contiguousFrom` returns null for
  anything else, and SABR answers from a segment boundary — real UMP runs measure 32769B, so an
  arbitrary scrub byte is a usable key roughly once in 32769 attempts.
- **`storeMedia` discards a straddling run whole** (`if (offset < served) return 0`) rather than
  trimming it, so the segment that actually covers a seek target is thrown away.

So a granted seek would have looked exactly like a refused one. That is the defect that made the
warm-jump experiment unable to succeed.

## ⚠️ The map has been unreliable here: session continuity is REOPENED

An earlier version of this file said "Session continuity is **not** the missing piece" and told the
reader to cross the lead off. **That conclusion was not earned and this file asserted it anyway.**

The experiment was `SabrServesWhatWeChooseTest.aJumpInsideAnEstablishedConversation`: read sequentially
from the start, then jump. Its output was

```
warmed the conversation with 1892KB over 4 reads, now at 1938378B
then jumped to 15383805B and got 0KB
fetches=8 served=1938378B segments=4[1, 2, 3, 4] mediaTime=407499ms
```

and three things make that unsound as a negative result:

1. **It judges at the READER, not on the wire.** Unlike the cold-jump arm it uses the plain transport
   and decides on `jumped.isEmpty()` — a value produced downstream of every reader defect in the
   section above. It cannot tell "the server sent nothing" from "the server sent plenty and we could
   not key it".
2. **Its success criterion was unsatisfiable by construction.** It asks for `length / 2`, an arbitrary
   mid-segment byte, and `read` requires `chunks[from]` to exist exactly. Even a perfectly served jump
   returns empty.
3. **The target is past the ~1MB ceiling**, the same confound as the cold arm.

So the lead is **open again**. It may still be wrong — nothing here says warm jumps work — but nothing
here says they do not either.

⚠️ This is the second unsound instrument in this file's history. The first was caught: `aimAtByte`
originally fired only on a *cold* open, so the warm probe skipped it and asked for 130005ms, the
position sequential reading had already reached. That was found and fixed (aim on any discontinuity,
judged from `handedThrough`, since `contiguousFrom` consumes what it returns) and the re-run asking for
407499ms is what made the *aim* half real. The reader-side half was never fixed, and the conclusion was
published anyway. **Treat every "ruled out" line in this file as suspect until its instrument is named**
— and prefer a wire measurement to a reader one every time.

### What a sound version needs

- **Wrap the transport in BOTH arms**, so the answer is response bytes rather than reader output.
- **Aim at a real segment boundary** captured from the warm reads' own `MEDIA_HEADER`s (`startBytes` /
  `sequenceNumber`), never `contentLength / 2`.
- **Stay inside the first megabyte** so the attestation ceiling is not part of the measurement.
- Report the wire sizes and the headers, and only then say anything about continuity.

## Structural: the `DataSource` seam is what makes this hard

Everything above is a symptom of one design choice. SABR is **time-addressed**; Media3's `DataSource`
is **byte-addressed**. SmartTube plugs SABR in as a `ChunkSource` under `ChunkSampleStream`, so
ExoPlayer asks for *the chunk covering time T* and SABR answers in exactly those terms — there is no
byte space to reconcile and no seek arithmetic to get wrong. Its whole seek trigger is that an **empty
chunk queue means a seek**, because ExoPlayer drains the queue on one.

Under a `DataSource` we have to invent a byte↔time mapping instead, and that invention is where every
mechanism in this file comes from: `aimAtByte`, `timeOfByte`, `served`, `handedThrough`, `writeAt`, and
the exact-key match. None of them can be made correct, because the coordinate system they translate
into does not exist in the protocol.

## OPEN — Dewi's decision

Two routes, and the choice is not ours to make:

| | Cheap | Proper |
|---|---|---|
| What | restart the conversation at the seek target (fresh stream, aimed, ranges cleared) | move SABR under a `ChunkSource` — [sabr-as-a-chunk-source.md](sabr-as-a-chunk-source.md) |
| Cost | small; keeps the byte machinery and its guesswork | days; deletes the byte machinery outright |
| Also buys | nothing | adaptive quality (structurally unreachable under a progressive source), and the live init segment |
| Risk | a cold open is the thing YouTube answers with no media | a large change to the one path that plays today |

Dewi's steer of 2026-08-20 — *"i want it working without (cheap/worse) ways please i want this app to
be quality"* — points at the second, and `sabr-as-a-chunk-source.md` is designed on that basis. It is
recorded here as **open** rather than decided, because the cheap fix is genuinely available and the
attestation ceiling may make both moot until a PO token exists.

## Where to look next

1. **NOT the `SABR_SEEK` part.** UMP is the **server→client** framing, so `SABR_SEEK` is YouTube telling
   *us* it has repositioned; it is not something a client can send. The id was wrong too: the
   hand-written table called 43 `SABR_SEEK`, but per `UMPPartId` 43 is `SABR_REDIRECT` and `SABR_SEEK`
   is **45**. The table has since been regenerated from the proto — 10 of its 16 entries were wrong. A
   seek must therefore be expressed in the REQUEST, in `VideoPlaybackAbrRequest`/`ClientAbrState`
   fields we do not populate yet. Worth diffing our request against `LuanRT/googlevideo`'s protos for
   what a real player sends alongside `player_time_ms`.

   Ignoring `SABR_SEEK` was also flagged as a possible mis-attribution bug. **Checked, and it is not
   one:** `storeMedia` places bytes using the `MEDIA_HEADER`'s own `startBytes`
   (`writeAt[headerId] = known.startBytes`), never the offset we asked for. The server tells us where
   each run belongs and we honour that, so a silent reposition cannot land bytes in the wrong place.
   Handling `SABR_SEEK` may still be needed to *notice* a reposition; it is not a correctness risk.
2. **Re-run the warm probe as a sound instrument** (above), before anything is built on either answer.
3. **A byte↔time reconciliation layer** — trimming a straddling run and serving from a virtual byte
   space — if and only if the cheap route is chosen. Under a `ChunkSource` it is not needed at all.

## CI evidence: the SABR VIDEO reader stalls on a long stream, re-fetching one segment (2026-09-06)

`AnHourLongItemDoesNotRebufferTest.anHourLongVideoPlaysOnWithoutRebuffering` (SABR ON) fails on CI —
consistently, twice — while passing locally, which is a timing-dependent SABR reader defect, not the
plain ~1MB cap. From CI's own logcat (run 6fadd4f), the reader gets stuck:

```
[sabr] itag 137 REWINDING to 0B (last handed through 104401) — asking from 0ms instead of 30429ms
[sabr] fetch #86 itag 137 at 30429ms -> 2353154B response, 2150211B kept ... carried 137=2164437B
[sabr] fetch #87 ... at 30429ms -> 2353154B response, 2150211B kept ...   (identical, again)
[sabr] fetch #88 ... at 30429ms -> 2353154B response ...                  (and again)
[sabr] closed at 104401 — fetches=89 failed=1 served=605399B discarded=18202291B (96% wasted)
[playback] gave up buffering after 10631ms at 9507ms — it never recovered
[playback] stopped loading at 9507ms ... the tail is not coming
```

The server IS serving 2.3MB responses; the reader asks for media time 30429ms over and over, keeps
~2.1MB each time yet only ever hands ~104KB through, discards 96%, and never advances past ~9.5s. So
the stall is in how the reader requests/consumes SABR media time on a long stream, squarely the
[sabr-as-a-chunk-source](sabr-as-a-chunk-source.md) territory — a ChunkSource addresses segments by
position and would not re-ask for a segment it already holds. The video path is UNTOUCHED by the
2026-09-06 session (SABR code not modified); this is the standing SABR-machinery limitation, and the
emulator job is deliberately **not** a release gate (ci.yml:53) so it does not block the APK.

