---
title: Move SABR off DataSource and onto a Media3 ChunkSource
status: designed
severity: high
updated: 2026-08-20
---

# SABR belongs under a ChunkSource, not a DataSource

Dewi's decision, 2026-08-20: *"i want it working without (cheap/worse) ways please i want this app to
be quality"*. This is the proper fix for seeking, and it is the only route to adaptive quality.

## It also lifts the ~1MB ceiling, which was the real blocker

Established 2026-08-20 and the strongest reason to do this work: the server states
`target_audio_readahead_ms=15000` on nearly every response — it serves fifteen seconds beyond the
**playback position**. We send the end of our buffer instead, so we ask for fifteen seconds past what
we already hold and are told, correctly, that we have plenty. Four of those end the stream at about
1MB, and for two days that was blamed on attestation.

A `DataSource` cannot fix it: it is never told where playback is. `ChunkSource.getNextChunk` receives
`playbackPositionUs` directly. See
[sabr-stops-at-one-megabyte.md](sabr-stops-at-one-megabyte.md) for the measurements.

## Why the current seam cannot work

SABR is **time-addressed**. Media3's `DataSource` is **byte-addressed**. Everything painful in
`SabrStream` exists to bridge that gap and none of it can be made correct:

- `aimAtByte` turns a byte offset into a media time with a flat-bitrate ratio — a guess, and the
  server acts on it.
- `read(from)` needs `chunks[from]` to exist at the *exact* offset, while SABR answers from a segment
  boundary. Real UMP parts measure 32769B, so an arbitrary scrub byte lands on a usable boundary
  roughly once in 32769 attempts.
- `storeMedia` drops a straddling run whole rather than slicing it, so the segment covering a seek
  target is discarded.
- `served`, `handedThrough`, `writeAt`, `timeOfByte` are all bookkeeping for a coordinate system the
  protocol does not have.

Measured consequence, on this emulator on 2026-08-20: playback from the start is fine, and any reopen
at a non-zero offset serves **0B** — `itag 137 ended at 7721778 — 0B of 1363020340B (0%)`, having
downloaded and discarded 3015717B.

## Why it also blocks quality — PROVEN, not assumed

Automatic ABR is structurally unreachable under a progressive source, verified against the
media3 1.10.1 jars:

- `ExoTrackSelection.updateSelectedTrack(...)` is called **only** from `DefaultDashChunkSource.getNextChunk`
  and `HlsChunkSource`. Nothing in `androidx.media3.exoplayer.source` calls it.
- `ProgressiveMediaPeriod` consults the selection once in `selectTracks` and never again, and builds
  every `TrackGroup` with a one-element `Format` array — so it cannot even expose an adaptive group.
- With a single-track group, `AdaptiveTrackSelection$Factory` falls through to `FixedTrackSelection`,
  whose `updateSelectedTrack` body is `return`.

So the `DefaultBandwidthMeter` wired in `PlaybackService` feeds diagnostics and nothing else: on a
weakening connection the app stalls, because it has no mechanism to step down. That is the direct
enemy of "no buffering on an hour-long video".

A **manual** quality menu is a separate matter and does not need this work: `VideoResolver` computes a
real ladder and then throws it away (`qualities = emptyList()`), on the reasoning that offering a
switch while adaptation is unimplemented would lie.

## What SmartTube does, which is the shape to copy

It plugs SABR in as a `SabrChunkSource` under `ChunkSampleStream`, so ExoPlayer asks for *the chunk
covering time T* and SABR answers in exactly those terms — there is no byte space to reconcile. Its
seek trigger is three lines: an **empty chunk queue is the seek signal**, because ExoPlayer drains the
queue on a seek.

```java
long seekTimeUs = queue.isEmpty() ? loadPositionUs : C.TIME_UNSET;   // DefaultSabrChunkSource
startTimeMs = isInit ? 0 : seekTimeUs != C.TIME_UNSET ? seekTimeUs / 1000
                                                      : activeStream.getSegmentStartTimeMs(itag);
```

## The framework is already on our classpath

`media3-exoplayer` 1.10.1 (already a dependency) ships the whole `source.chunk` package:
`ChunkSource`, `ChunkSampleStream`, `MediaChunk`, `ContainerMediaChunk`, `BundledChunkExtractor`,
`InitializationChunk`, `BaseMediaChunkIterator`. `media3-exoplayer-dash` is also already a dependency,
so `DashMediaPeriod` is available as a template.

The contract to implement:

```
getNextChunk(LoadingInfo, long playbackPositionUs, List<MediaChunk> queue, ChunkHolder out)
getAdjustedSeekPositionUs(long positionUs, SeekParameters)      <- snap to a segment boundary
getPreferredQueueSize(long, List<MediaChunk>)
shouldCancelLoad(long, Chunk, List<MediaChunk>)
onChunkLoadCompleted(Chunk) / onChunkLoadError(...) / maybeThrowError() / release()
```

`MediaChunk(DataSource, DataSpec, Format, trackSelectionReason, trackSelectionData, startTimeUs,
endTimeUs, chunkIndex)` — time-addressed, with an index. Note a chunk still takes a `DataSource`:
ours becomes **one segment per chunk, always opened at 0**, which removes the byte-offset problem
rather than working around it.

## Plan

1. `SabrChunkSource : ChunkSource` in `:core:playback`, holding the SABR conversation and a segment
   index built from `MEDIA_HEADER` (`sequence_number`, `start_ms`, `duration_ms`, `start_bytes`).
   `getNextChunk` uses the empty-queue seek signal; `getAdjustedSeekPositionUs` snaps to a real
   segment start.
2. `SabrMediaSource` / `SabrMediaPeriod` creating `ChunkSampleStream`s per track, modelled on
   `DashMediaPeriod`, replacing the `MergingMediaSource` pair for the SABR case only. Podcasts,
   local files and direct URLs keep the existing progressive path untouched — the seam stays one
   `MediaSource.Factory`.
3. Segment parsing via `BundledChunkExtractor` (fMP4), with the init segment as an
   `InitializationChunk`. This is also what unblocks **live over SABR**, which is refused today for
   exactly the missing init segment.
4. Expose a real adaptive `TrackGroup` so `AdaptiveTrackSelection` engages, then let
   `VideoResolver` publish its ladder instead of `emptyList()`.
5. Delete `aimAtByte`, `timeOfByte`, `served`/`handedThrough`/`writeAt` and the whole byte cursor.
   `SabrStream` keeps only the conversation: request, absorb, buffered ranges.

## Guardrails

- The four proven bugs being fixed in the current pass are all in the byte machinery this work
  deletes. Land them first anyway: they are what makes the app usable today, and step 5 must not be
  the thing that fixes them, or there is no test that ever saw them fail.
- Podcast playback and downloads must not change at all. The SABR path is one branch of one factory.
- Keep the live refusal explicit until step 3 actually serves an init segment; a silent attempt that
  stalls is worse than a named refusal.
