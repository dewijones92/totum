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
