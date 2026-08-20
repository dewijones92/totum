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
