---
title: SABR cannot be opened part-way through
status: open — the server now serves a cold jump (embedded endpoint, 2026-09-07); the reader cannot consume one, which is the ChunkSource redesign
updated: 2026-09-25
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

## ✅ 2026-09-07: a cold jump IS served — on the EMBEDDED endpoint

The "THEIRS" finding below was measured on the ANDROID endpoint, which is the one that also caps at ~1MB
([sabr-stops-at-one-megabyte.md](sabr-stops-at-one-megabyte.md)). Against the embedded player's endpoint
(`tools/potoken/embeddedsabr.py`, no token) the patient probe opened the conversation at **3,600,000ms
of the 97-minute fixture** (`-DstartAtMs=3600000`) and was served straight away:

```
fetch  5 asked 3657156ms -> 535617B media   distinct 1138KB  furthest byte 60735KB
fetch 10 asked 3757305ms -> 550976B media   distinct 2053KB  furthest byte 61598KB
fetch 20 asked 3958375ms -> 511128B media   distinct 3856KB  furthest byte 63297KB
```

The first media header's offset was the hour mark's (60.7MB into the file), `protection=status=1`, ~500KB
of fresh media a fetch, no init-only answers. **The server is not the obstacle to seeking any more.**
What remains is entirely ours, and it is the reader's design: ExoPlayer asks a progressive `DataSource`
for BYTE `from`; the stream aims by a bytes→time ratio and the server answers with whole segments that
start at their own boundaries, so the byte at exactly `from` is not what arrives and the read stalls.
For audio the ratio is close and a segment-boundary re-base would work; for VBR video it is wrong by
seconds. Both are the [ChunkSource redesign](sabr-as-a-chunk-source.md), which asks for a TIME and hands
ExoPlayer whole chunks. Until then the resolver still refuses SABR for a resumed item, and rightly.

## THEIRS: no media for a cold jump — wire-measured (ANDROID endpoint, 2026-08-20)

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


### Three more runs, 2026-09-20 — two mechanisms proposed for it, both disproved

The same failure, in three consecutive CI runs, with the numbers above reappearing verbatim
(`104401`, `30429ms`, `2353154B response, 2150211B kept`, `closed at 104401`).

**The sequence, in full, from run 35520676271** — because it is two failures, not one, and only the
first was previously written down:

```
16:13:01  play uSMGENDH_QI … for play OVER SABR
16:13:05 → 16:13:24   ×5   [sabr] stalled waiting for byte 104401 — failing so recovery can re-resolve
16:13:24  [resolve] SABR stalled on uSMGENDH_QI; extracting for it from now on this session
16:13:44  [resolve] uSMGENDH_QI in 20262ms … → play from …/hls_playlist/…
16:14:03  [playback] gave up buffering after 19255ms at 0ms — it never recovered
```

So SABR wedges at 104401, the app correctly gives up and re-resolves by extraction — **and then the
HLS replacement never buffers either, from position 0.** That second half is the `rendered only
-41ms` / `9566ms -> 0ms` in the assertion message, seen from the other side: the recovery did not
merely lose the place, the stream it switched to never started. Whoever picks this up needs both.

This does not generalise across the runs: run 35515542462's failing test has **no HLS play at all**
(three `videoplayback` plays, the last logged `rescued … over SABR`), so its 60-second window was
measured on a live SABR conversation. An earlier version of this section said every failure was
reported against a non-SABR stream; that is true of runs 2 and 3 only.

**The open lead, which is the one thing here with an experiment attached.** `itag 137 gave nothing at
30429ms … NOT ending, skipping ahead` fires after the fourth empty answer, lands the conversation at
~3.1 MB, and the reader is still at 104401 waiting for the byte after what it holds — segments 3, 4
and 5 are never fetched. **Whether the skip creates the gap or merely follows one is not
established**, and the next person should establish it before changing anything. It sits alongside
this file's existing hypothesis rather than replacing it.

### The skip does not create the gap, and the byte-to-time estimate is why (2026-09-20)

**The lead above is closed, with a control.** Four consecutive fetches ask at the SAME time and get
the SAME three runs back — `init`, `seq 1`, `seq 2`, bytes 0..104401 — before any skip fires:

```
fetch #2 itag 137 at 429ms -> 289189B response, 0B kept   (retry 1 of 3)
fetch #3 itag 137 at 429ms -> 289189B response, 0B kept   (retry 2 of 3)
fetch #4 itag 137 at 429ms -> 289189B response, 0B kept   (retry 3 of 3)
fetch #5 itag 137 at 429ms -> 289189B response, 0B kept   -> skipping ahead
```

Segment 3 is never offered, and it is never offered *before* the skip. **The skip follows the gap.**

**Why it asks for 429ms is arithmetic, and it matches to the digit.** The reader holds up to byte
104401 and converts that to a media time with `HeldSegments.timeOfByte`, which is
`offset * durationMs / totalBytes` — a whole-file constant-bitrate ratio:

```
104401 × 5805166 / 1411564633 = 429.4  →  the logged "at 429ms"
```

But those 104401 bytes are the init segment plus two real segments, which carry far more than 0.43
seconds of a NASA documentary's low-motion opening. So the app asks the server for a time it has
already been served, is handed those same segments back, discards them, and reports "no bytes".

**It usually takes that fallback** (an earlier version said "always", and said "there is no case in
hand where the accurate path is taken" — both false, and refuted by row 3 of the table below, which
asked `19,767 ms` via `contiguousEndMs`. The retraction was written and then lost in a later edit,
which is why it is restated here). `HeldSegments` prefers a segment's real `startMs` and only falls
back to the ratio when it is null — and across the three runs **1,513 MEDIA_HEADERs carry no
`startMs` at all** (every one logs `:at-1`, which is the null). There is no case in hand where the
accurate path is taken.

**The controlled comparison, run 35525069446.** Three itag-137 streams, one run, one video, one
build — and the first answer is *identical* in two of them:

| stream | first fetch | what it asked for NEXT | outcome |
|---|---|---|---|
| 17:30:27 (playback) | `289187B → 104401B kept` | **429 ms** — the RATIO | 0B kept ×4, stalls, gives up |
| 17:32:58 (`SabrKeepsServingTest`) | **`289187B → 104401B kept`** | **10,000 ms** — the STEP ladder | 1,538,361B kept, plays on, 11.3 MB |
| 17:37:02 (playback, passing) | `1977342B → 1642762B kept` | 19,767 ms — `contiguousEndMs` | plays |

**So the variable is not how much the first answer contained** — an earlier version of this file said
it was, and the middle row refutes it with the same bytes on both sides.

**But "ask past what you hold" is not sufficient either, and the counterexample is forty lines away
in the same logcat.** The itag-251 audio track of the 17:30 play asks 57,271 ms, then 87,271 ms,
117,271 ms and 147,271 ms — all far beyond its frontier — and keeps **0 B every time**, dying at
979,459 B. That is the ~1 MB ceiling this repo already names elsewhere, i.e. a different cause that
this rule cannot tell apart from the first. **So the honest claim is narrow:** asking for a time
*behind* the frontier is ONE way to be served nothing, it is demonstrably what happens to itag 137
at 429 ms, and it is fixable in our code. It is not the only way.

**Row 2 is not a clean control, and saying so is the point.** `SabrKeepsServingTest` builds its
stream with **no `durationMs`**, which changes two things on the wire at once: `timeOfByte` returns
null so the claim falls through to the live-stream `+ stepMs` ladder (that is where the neat
10,000/20,000/30,000 comes from — not a design choice), and `asRanges` returns empty, so that stream
declares **no buffered ranges at all** while the stalling one declares `described=1`. Rows 1 and 3
differ in three ways. No pair in this table isolates a single variable; the table shows that
first-answer size is not it, and nothing more.

**Falsifier for what is left:** the claim dies if a stream is found asking for a time *behind* its
frontier and being served new bytes anyway, or if itag 137 at 429 ms is served after the claim is
corrected. Test it by fixing the claim, not by arguing about it.

The arithmetic says why. Those 104,401 bytes are `init 14,226 + seq1 48,478 + seq2 41,697` ≈ **9.9
seconds** of media (the passing stream's `contiguousEndMs` after four segments was 19,767 ms). The
ratio claims **429 ms** — `104401 × 5805166 / 1411564633` — which is **23× behind the truth**,
because a VBR video's opening segments are tiny against its mean bitrate. A claim behind the frontier means the server answers with the segment
containing that time — which is exactly what the log shows it doing, `carried … 137=104401B`, the
same `init+seq1+seq2` again — and `absorb` discards all of it as already held: 0 B, four empties,
dead. (An earlier version reached for the server's "~15 s readahead" here. That figure is
`target_audio_readahead_ms`, an AUDIO target, and `NEXT_REQUEST_POLICY` is in the `ignored parts`
list on every one of these responses. The re-sent bytes are the direct evidence and need no
arithmetic.) The step ladder asks 10,000 ms, lands just past the frontier, and is served.

`HeldSegments.kt` already says in its own comment that the ratio is unsound for video. This is what
that costs.

**Two earlier explanations for this failure were written and retracted**, and the discipline is the
point: "a replay rewound a warm stream" (disproved — a cold stream failed identically) and "the
embedded player refused it so SABR fell back to a capped client" (disproved — the run that DID use
the embedded player failed the same way, and the "capped" client had served 11.3 MB in the same
run). A third framing, "the first answer was too small", is retracted here by the table above. Find
the control before naming a cause.

**What the new runs mostly add is negative evidence**, which is worth as much:

- **Client identity is ruled out.** `[sabr] … SABR session from the X player` counts are
  EMBEDDED/ANDROID = **12/8, 2/6, 0/18**; the test failed identically in all three, and run 1's
  failing play was itself on ANDROID. An explanation built on "the embedded player refused it, so
  SABR fell back to a capped ANDROID client" was written and retracted — `SabrKeepsServingTest`,
  ANDROID, same video, served **11,315,189 bytes** in every one of the three runs, so there is no
  cap to blame. (That retraction first quoted the counts as "12/0, 2/0, 0/11". The 11 was the
  *embedded-refusal* count in run 3, a different metric on the other side of the slash.)
- **Stream reuse is ruled out.** Only run 3's build contains the change that drops held
  conversations on a fresh start, and only run 3 logs `dropped 1 held stream(s) … so a replay opens
  cold` — that line, not `opened at 0 … (open #1)`, is what distinguishes the cases, because the
  latter fires on every open including a warm reuse. Run 3 opened cold and failed byte for byte the
  same. An explanation built on "a replay rewound a warm stream and discarded what it re-fetched"
  was also written and retracted; `read(from)` sets `served = from` immediately after re-aiming, so
  the guard it named is inert.

**Note on this file's framing.** Its opening line says SABR "is offered only as a rescue". That is
not true of `AnHourLongItemDoesNotRebufferTest`, which sets `setSabrPlayback(true)` and makes SABR
the **primary** route; SABR fires as a rescue later in the same cascade. The distinction matters
because the audio-only rung is reachable from the rescue ladder and not from the primary path, so
"skip SABR and go straight to audio" is not a one-line change.

**Keep the test red.** It asserts something the app cannot currently deliver on this route, which is
honest. Softening it is what turned a real breakage into five green days in August.

### Recovery went back to SABR twice after the stall, and that part is fixed (2026-09-25)

This is about what happens AFTER the stall above, not about the stall. The 429 ms claim is untouched and
still the lead; this only changes how long the player spends on a route already known to be dead.

Five main runs read (a32957a, 387d8fc, 70ea678 failing; 409f75d, 70df726 passing), from each run's
per-test logcat in the `instrumented-reports` artifact. In all three failing runs the sequence is:

```
re-resolving after Rejected (attempt 1 of 1) from 9510ms
prefetching uSMGENDH_QI
SABR stalled on uSMGENDH_QI; extracting for it from now on this session   <- 0-1 ms LATER
uSMGENDH_QI in 1182ms for prefetch OVER SABR                               <- so the check had already passed
...
stream still failing after 1 recoveries; giving up on the stream
uSMGENDH_QI in 170ms for rescue OVER SABR                                  <- the rescue rung never checked
```

Two defects, both in our code:

- **A race.** The stall was recorded by a second, independent collector of `streamFailures` in
  `AppContainer`, while `StreamRecovery` collected the same flow and replays at once on attempt 1. Nothing
  ordered them, and in all three runs recovery won, so the one re-resolve went back over SABR. The guard
  (`stalled over SABR earlier; extracting instead`) only fired on the resolve after that. Now
  `StreamRecovery` records the stall itself (`onSabrStalled`) before it replays.
- **The rescue rung ignored the stall.** `resolveAsRescue` never consulted `sabrStalledOn`, so once
  recovery gave up, the rescue offered SABR a third time. It now declines for an item SABR stalled on.

Together they put HLS about 50 s into the run, after the 60 s watch had mostly gone. Tests:
`StreamRecoveryTest` (the stall is recorded before the replay) and `SabrIsNotRetriedAfterItStallsTest`
(the rescue declines); both failed at their named assertion first.

**What this does NOT claim.** It does not make `AnHourLongItemDoesNotRebufferTest` pass: the stall at
~9.5 s is still a rebuffer, and the test allows none. The passing runs did not pass because of recovery at
all; they started on HLS or a direct URL (409f75d joined an extraction already running) and never met the
stall. Expected after this change, and to be checked against the next CI run rather than assumed: the same
first stall, then extraction instead of SABR, so `rendered` rises well above 9.5 s while the rebuffer
assertion still fails. A local run on `totum-api35` is no control for either: it never took SABR.

