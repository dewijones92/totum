---
title: SABR is capped at ~1MB per format, so it cannot carry a whole video
status: open
severity: blocker
updated: 2026-08-20
---

# SABR stops at ~1MB, and everything else about SABR is downstream of that

Measured on `totum-api35`, 2026-08-20 17:56, `AnHourLongItemDoesNotRebufferTest` with a ten-minute
soak on `uSMGENDH_QI` (97-minute NASA VOD, audio itag 251 @168kbps).

**Every audio stream in the run ended between 968840B and 990078B.** That is ~0.93 MiB, about 57
seconds of Opus. It is the ~1MB attestation ceiling, not a coincidence and not our arithmetic.

## What the wall looks like from inside

```
fetch #6  at  47872ms -> 346444B, 160745B kept          <- healthy
fetch #7  at  57271ms -> 184734B,      0B kept, carried 251=171364B
fetch #8  at  87271ms ->  23768B,      0B kept, carried 251=10619B    <- init segment ONLY
fetch #9  at 117271ms ->  23768B,      0B kept, carried 251=10619B    <- init only
fetch #10 at 147271ms ->  23768B,      0B kept, carried 251=10619B    <- init only
closed at 979459 — served=979459B discarded=1036735B (51% wasted)
the held stream for uSMGENDH_QI itag 251 is spent — starting a fresh one
  fresh stream: served=0B discarded=256038B (100% wasted) -> spent -> fresh -> ...
```

Past the ceiling the server answers every request with the **initialization segment and nothing
else**. Our four-empty budget then ends the stream, the cache correctly drops it as spent, a fresh
stream is built, and the fresh one dies the same way. **Eighteen restarts in a single ten-minute
run.** The restart loop is a faithful reaction to a wall it cannot see.

## ⚠️ SUPERSEDED: the cause is known now, and it is not attestation

Everything above stands as measurement. The *diagnosis* was wrong, and the server had been telling us
the answer on every single response in a part we discarded:

```
NEXT_REQUEST_POLICY: targetAudioReadahead=15000ms targetVideoReadahead=15000ms
                     maxSinceLastRequest=60000ms cookie=77B
```

**It serves fifteen seconds beyond where PLAYBACK is.** `player_time_ms` means the playback position.
We derive it from the furthest byte HELD — the end of the buffer — so once the buffer is a minute long
we ask for a minute and fifteen, and the honest answer to that is an initialization segment and
nothing else. Four of those end the stream. That is the entire ~1MB ceiling.

Proven on 2026-08-20: a probe sending a real playback position instead of the buffer end went from
**1104KB to 1732KB**, with a 332864B response arriving exactly where an init-only reply had been.
Nothing else changed — no token, no client info, no cookie.

So the PO-token thesis is dead. The refusal never raised `status=3` because nothing was being refused;
we were asking a question that had already been answered.

### Two attempts at fixing it without the seam move, both reverted

**1. Derive the claim from the reader's offset.** `served` is the closest a `DataSource` has to a
playback position. On the device the loader pulls a megabyte in about a second, so this claims
forty-six seconds after one — no improvement, and **one rebuffer where there had been none**.

**2. Report the real position through a side channel.** `Media3PlaybackController` holds the player,
so it published `currentPosition` for `SabrStream` to read. Also no improvement, and the logs say
exactly why: `fetch #3 itag 251 at 65173ms` while that stream had served 46 seconds of audio. The
position belonged to the **fallback**, which by then was what was actually playing. One global number
cannot serve two tracks, a download, and a fallback that may or may not be the current player.

Keying it per video would not help either: the fallback plays the same video. The position has to come
from the loader driving *this* stream, which is the framework's job and nobody else's.

### Four interventions tried on the device, none of which moved it

| tried | result |
|---|---|
| claim derived from the reader's byte offset | no change; and in an earlier run, one rebuffer where there had been none |
| claim from the real player position, via a side channel | no change — the position it read belonged to the FALLBACK, which was by then what was playing |
| `MAX_BUFFER_MS` cut from 240s to 14s, inside the server's readahead | no change: still 979459B, still 14 fetches |
| `PLAYBACK_BYTES` cut from 64MB to 320KB (~15s of Opus) | no change: identical to the byte |

The last two are the informative ones, and they say something the first two do not: **ExoPlayer's
load control cannot restrain this at all.** The fetch log explains why —

```
fetch #1 at     0ms -> 185586B response, 172220B kept
fetch #2 at 10070ms -> 185936B response, 161948B kept
...
fetch #6 at 47872ms -> 346444B response, 160745B kept
fetch #7 at 57271ms -> 184734B response,      0B kept
```

Ten fetches inside a handful of `read()` calls, all in about a second. `SabrStream.read` loops
internally until the byte it was asked for arrives, so a single blocking `DataSource.read` can pull a
megabyte and race the claim from zero to fifty-seven seconds. ExoPlayer never sees those requests and
has no opportunity to say "that is enough" — which is exactly what a load control is for.

So the claim is not wrong because of arithmetic. It is unrestrainable because the fetching happens
inside a blocking read that nothing supervises.

### Why it is not simply fixed

Because a `DataSource` is never told where playback is. `served` is the LOADER's offset, and on a
device the loader pulls a megabyte in about a second — so deriving the claim from it asserts
forty-six seconds after one. Tried on `totum-api35`: no improvement, and **one rebuffer where there
had been none**, so it was reverted.

The number the server wants is handed to a chunk source and nowhere else:

```
ChunkSource.getNextChunk(LoadingInfo, long playbackPositionUs, List<MediaChunk> queue, ChunkHolder)
```

**So [sabr-as-a-chunk-source.md](sabr-as-a-chunk-source.md) is not only the fix for seeking and for
ABR — it is the fix for this ceiling too.** Three separate problems, one seam, and this is the one
that makes SABR unable to carry a video at all.

## Two things this reframes

**The video arm's clean result was not SABR.** The ten-minute video soak recorded zero rebuffers,
and it played from `manifest.googlevideo.com/api/manifest/hls_playlist/...` — it had fallen back to
HLS. No video-itag SABR stream appears anywhere in the run's stream-close lines. Attributing that
result to SABR was wrong.

**The across-content-types result was measured under the wall.** `SabrPlaysAcrossVideoTypesTest`
watches each fixture for about ten seconds, and ten seconds of any format is far short of 1MB. Four
of five types resolving and showing a picture is true and worth having, and it says nothing about
whether SABR can carry a video to its end. It cannot.

## Therefore

The four byte-machinery bugs being fixed in this pass are real, and they are not what stops SABR
working. Neither is the `DataSource`-versus-`ChunkSource` seam: a chunk source asking for segment 40
gets the same init-segment-only answer. **The blocker is attestation — a PO token.** Every other SABR
problem is only observable in the first megabyte.

`youtube-requires-attestation.md` already named this as the leading candidate and could not confirm
it. This confirms it, with the ceiling measured on eighteen independent streams.

## What is NOT capped

- Direct extracted URLs: a ranged request at an 8,000,000B offset returned **HTTP 206 (102400B)** on
  the same video in the same session.
- HLS: ten minutes of continuous playback, zero rebuffers.

So the app has working paths today. What it does not have is a SABR path that survives a minute.

## What was fixed once the ceiling was understood (2026-08-20)

None of these lift the ceiling. They stop the app making it worse, and they are what took the
SABR path from stalling to handing over cleanly. Measured on `totum-api35`, five-minute audio soak
each time:

| | streams built | restarts | retry backoff | rebuffers |
|---|---|---|---|---|
| before | 15 | 14 | 3600ms → 37656ms | 1–2 |
| after | **1** | **1** | none (1ms) | **0** |

- **A death that served nothing ends SABR for that track.** The cache was doing as told — drop the
  spent stream, build a fresh one — and each fresh one spent its four-empty budget against the same
  wall. About 3.5MB fetched and discarded per attempt.
- **The refusal is not retried.** It went out as a plain `IOException`, so Media3's default policy
  retried it ten times with exponential backoff and the extraction fallback did not begin for about
  thirty-eight seconds. That delay *was* the stall, and it survived the bandwidth fix.
- **Giving up needed a third answer, not a second.** `null` meant both "no session, fall through to
  ordinary HTTP" and "SABR is finished here"; answering the second with the first is a `GET` at a
  POST endpoint, reading a refusal body and handing it to the extractor as media.

So with SABR enabled the app now reaches the wall, gives up in about a second, and plays the item
through extraction with **zero** rebuffers. SABR still cannot carry a video, and that is attestation.

## Characterised precisely (2026-08-20, `SabrSegments`)

A clean segment-addressed source was written specifically to remove every suspicion about our request
shape: **one request per call**, at a position the caller states, no internal loop, whole segments in,
`client_info` set, playback cookie echoed. `SegmentsAreAddressedByTimeTest` pins those properties.

Against live YouTube it stops at **946KB covering exactly 60001ms of media**, paced to real time or
not:

```
call 1: seq=1 covers 10001ms, 157KB held
call 6: seq=6 covers 60001ms, 946KB held
nothing new at 60001ms from 11074B — parts PLAYBACK_START_POLICY, STREAM_PROTECTION_STATUS,
    REQUEST_CANCELLATION_POLICY, FORMAT_INITIALIZATION_METADATA, NEXT_REQUEST_POLICY,
    MEDIA_HEADER, MEDIA(10620B), MEDIA_END   protection=status=2
```

The response at the ceiling carries the **initialization segment and nothing else**. No `SABR_ERROR`,
no `RELOAD_PLAYER_RESPONSE`, no reason of any kind, and the same protection status as the responses
that served megabytes. The server is not refusing us — it has no media to give for that position.

**So the ceiling is about sixty seconds of media per session**, and it is independent of: request
shape, internal looping, honest positions, real-time pacing, buffered-range descriptions,
`client_info`, the playback cookie, a correctly-bound PO token in either documented location, and
ExoPlayer's buffer policy by duration and by bytes. Eleven things ruled out.

What a client must do at the sixty-second mark that we do not is still unknown. The paced probe that
once reached 1732KB is the only evidence anything ever went further, and it is unexplained.

## The documented protocol surface is now exhausted

Fifteen things have been varied against a live, working endpoint. **The number never moved: 946KB,
covering exactly 60001ms.**

| # | varied | result |
|---|---|---|
| 1 | PO token in `streamer_context.po_token` | no change |
| 2 | same token as `pot=` on the URL | no change |
| 3 | token bound to `videoId` vs `visitorData` | no change |
| 4 | `streamer_context.client_info` naming the right client | no change |
| 5 | `streamer_context.playback_cookie` echoed | no change |
| 6 | `streamer_context.sabr_contexts` echoed | no change |
| 7 | `selected_format_ids` sent once initialised | no change |
| 8 | `buffered_ranges` describing real held segments | no change |
| 9 | claim derived from the reader's byte offset | no change |
| 10 | claim from the real player position | no change |
| 11 | one request per call, no internal loop | no change |
| 12 | paced to real time at the stated 15s readahead | no change |
| 13 | paced with a 5s margin inside it | no change |
| 14 | `MAX_BUFFER_MS` 240s → 14s | no change |
| 15 | `PLAYBACK_BYTES` 64MB → 320KB | no change |

And the server's own words rule out the obvious explanations:

- `FORMAT_INITIALIZATION_METADATA` says **`endTimeMs=5805201 endSegment=581`** — it knows the format
  runs the full 96.75 minutes. The media exists; this is not a windowed session.
- `protection=status=2` on the refusing response, the same as on the ones that served megabytes. Not
  an attestation refusal.
- No `SABR_ERROR`, no `RELOAD_PLAYER_RESPONSE`, no reason of any kind. Just
  `MEDIA_HEADER + MEDIA(10620B) + MEDIA_END` — the initialization segment, alone.

**So: a hard sixty seconds of media per session, unexplained, and not reachable from any field in
`video_playback_abr_request.proto` that any reference implementation populates.**

## The limit, established (2026-08-20)

**Over SABR this client can obtain the first sixty seconds of a video and nothing more, by any means
available to it.** That is not a summary of a hunch; it is what sixteen variations and three structural
probes measured, all against endpoints that demonstrably serve.

What the server does:

- A session serves **exactly 60001ms** of media from a cold start, then returns the initialization
  segment and nothing else — **permanently**. Waiting 3s, 10s and 30s does not restore it, even though
  the policy starts advertising `backoff=2000ms` at that point.
- **No requested position helps.** Swept 0, 15000, 30000, 45000, 55000, 59000, 60001, 65000 and
  90000ms with the buffer declared: every one returned zero new segments. So every position-shaped
  theory is dead, including the readahead one.
- **A fresh session cannot resume.** A brand-new endpoint asked for 60001ms serves *nothing at all* —
  not even segment one. A session can only begin at zero. This matches the very first finding of the
  whole investigation, that a cold mid-stream open is answered with no media.
- Throughout: `endSegment=581` (it knows the format runs 96.75 minutes), `protection=status=2` (the
  same as a success), and no `SABR_ERROR` or `RELOAD_PLAYER_RESPONSE` of any kind.

Sixteen request-level variations changed nothing — the fifteen in the table above, plus the one that
had genuinely never been run: **the correct segment shape, a WEB endpoint whose `n` is solved so it
actually answers, and a proof-of-origin token bound to the same `visitorData` that player request
used.** Every earlier token measurement had used the byte-addressed reader, and most of them an
endpoint that was quietly returning 403.

So this is a limit on what the client is **permitted**, not a defect in how it asks. Sixty seconds
from a cold start, no resumption, no recovery, no stated reason. It has the shape of a preview
allowance, and nothing we can send lifts it.

## SmartTube's request, replicated field for field — and it changes nothing

The last thing left that was not a guess was to read the request builder of a client that
demonstrably streams whole videos over SABR on the same broadband. `SabrManifest.java` in SmartTube's
own ExoPlayer fork is that builder, and our request now matches it:

- **`ClientAbrState`**: `player_time_ms` (28), `enabled_track_types_bitfield` (40),
  `client_viewport_is_flexible` (22), `bandwidth_estimate` (23), `playback_rate` (35),
  `drc_enabled` (46), `sabr_force_max_network_interruption_duration_ms` (68).
- **`BufferedRange`** in SmartTube's own shape, which is the opposite of the obvious one and worth
  stating plainly: it reports the **last segment only**, as both start AND end index, with one
  segment's `duration_ms`, `start_time_ms = 0` (its source comments the field "not used"), and a
  `time_range` in ticks at timescale 1000 — a field we had never sent. Declaring the whole sixty
  seconds we hold reads, against a fifteen-second readahead, as "this client is full".
  Its comment describes naming the wanted segment through `player_time_ms` as *"cheating a bit by
  abusing the player time field"*. That is precisely what it does, and what works for it.
- **`selected_format_ids`** once initialised, and the full `streamer_context` — `client_info`,
  `po_token`, `playback_cookie`, `sabr_contexts`.

**Eighteen variations. Still exactly 60001ms, every time.**

So the difference between this app and SmartTube is **not in the request body**, which is the useful
part of this result. What is left is *who the client is and what it is entitled to*: SmartTube's
endpoint comes from a TV player response and it carries a whole attestation subsystem —
`PoTokenGate`, the NewPipe-derived `potokennp2`, and a **cloud fallback** for devices that cannot run
the BotGuard challenge locally. We mint a WEB token for a WEB endpoint.

There is nothing further to read. The next step is observation, not deduction.

## The client axis: we cannot obtain a TV player response at all

Since the request body is exhausted, the remaining difference from SmartTube is **which client asks**.
SmartTube is a TV client; every endpoint tested here has come from a WEB or ANDROID response. So
`tools/potoken/tvsabr.py` asks as `TVHTML5`, with a visitorData, a real signature timestamp and a
videoId-bound PO token in `serviceIntegrityDimensions` — the same four-part shape that made the WEB
request work.

```
[tvsabr] status=UNPLAYABLE reason=The page needs to be reloaded. endpoint=False config=False formats=0
```

**We cannot get a TV SABR endpoint to test.** And that is the *same* error, word for word, that
[signed-in-player-is-unplayable.md](signed-in-player-is-unplayable.md) recorded for the signed-in TV
player on 2026-08-20 — which is what gates age-restricted playback.

### One blocker, two symptoms

That convergence is the useful part of this. The TV client path is broken for us, and it gates:

1. **age-restricted videos** — `AppContainer.accountPlayer` tries `playerDowngradedTv` then
   `playerAsAccount`, and both answer `UNPLAYABLE: The page needs to be reloaded`;
2. **very probably SABR past sixty seconds**, since a TV client is what SmartTube is.

So the next objective is narrow and nameable rather than open-ended: **make a TVHTML5 player request
succeed.** `"The page needs to be reloaded"` is associated with a rejected client context — a stale
`clientVersion`, a wrong or missing `visitorData` for that client family, or a signature timestamp the
client is not expected to send. Ours read 20684 and then 20681 two minutes apart, which is itself
unexplained. That is a bounded problem with a clear success signal, unlike anything else left here.

## Observed on the Fire Stick (2026-08-20, read-only)

SmartTube **32.10** was in the foreground on a playback activity, `media_session` reporting
`state=3` (playing), with seven established HTTPS sockets — actively streaming on the same broadband
where our client and yt-dlp both cannot reach the TV client path. Read-only throughout: no install, no
clear, no key events, and the adb session disconnected afterwards. Its release build does not log SABR
internals, so how it does it is not visible from outside.

That completes the evidence and bounds the remaining unknown exactly:

- SmartTube, a TV client, streams whole videos over SABR here. **Confirmed by observation.**
- The TV client is refused to us and to yt-dlp from the same address. **Confirmed by two tools.**
- Our request body matches SmartTube's field for field. **Confirmed by replication.**
- The ceiling is sixty seconds regardless. **Confirmed by eighteen variations.**

So the difference is in what SmartTube presents as its identity — its own attestation stack
(`PoTokenGate`, `potokennp2`, and a cloud fallback for devices that cannot run the challenge) plus
whatever makes YouTube accept it as a television. Seeing that needs its requests on the wire, which
means a MITM proxy and a trusted CA on that device.

**That is Dewi's call, not something to set up unattended on a TV in use**, and it is the only step
left that is observation rather than deduction.

## Next

1. A PO token provider, minted per session and carried in `streamer_context` (field 19.2) — which
   our `VideoPlaybackAbrRequest` has no field for at all, so this is request work as well as
   provider work.
2. Until then, **do not treat SABR as the primary path**, and make the wall legible rather than
   letting it present as a stall: `STREAM_PROTECTION_STATUS(58)` arrives on these responses and is
   currently binned by `absorb`, and an init-segment-only answer is a distinctive shape worth naming
   in one line instead of three empty-response warnings.
3. The restart loop needs a stop: a stream that dies at the same byte count as its predecessor must
   not be replaced by an identical one.
