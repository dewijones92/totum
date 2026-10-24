---
title: MP3 in an AVI is cut into whole frames before the decoder
kind: adr
status: accepted
updated: 2026-10-24
---

# 87. MP3 in an AVI is cut into whole frames before the decoder

- Status: Accepted
- Date: 2026-10-24

## Context

Report 0.1.709 (2026-10-24, "why it ain't playing??"): every episode of a torrent of old XviD AVIs failed at 0 ms
with `ERROR_CODE_DECODING_FAILED`, `Decoder failed: c2.android.mp3.decoder` (the decoder released itself after
"work failed to complete: 14"). The files are ordinary (CBR 128 kb/s MP3, 44.1 kHz; ffmpeg decodes them cleanly),
but the MP3 is stored as a byte stream (`nBlockAlign` 1, `dwSampleSize` 1) in chunks that straddle frames (784,
356, 724 … bytes), behind a first frame of 417 zero bytes. Media3 1.10.1's `AviExtractor` hands each chunk to the
decoder whole, and Android's MP3 decoder takes only whole frames. ffmpeg writes one frame per chunk, which is why
files it makes play.

## Decision

- `totumExtractors()` (`:core:playback`, `AviMp3Framing.kt`) is Media3's `DefaultExtractorsFactory` with the AVI
  extractor wrapped; the player's media sources and the cast audio path both use it. Every other container is
  untouched, and the subtitle and GOP settings are passed through to the wrapped factory.
- On an AVI audio track whose format is `audio/mpeg`, `Mp3Framer` buffers the chunks and gives the decoder one
  MP3 frame at a time: it looks for a header confirmed by the next frame's, skips anything else (the zero frame),
  and times each frame from the chunk its first byte arrived in plus whole frames after it. A seek starts it again.
- Logged: `[avi]` when it starts re-framing, where the first frame was found, and the counts at each seek.

## Consequences

- AVIs with MP3 cut across frames play. A file whose MP3 is already one frame per chunk passes through as frames
  unchanged.
- The emulator's software MPEG-4 decoder handles Simple Profile only, so an XviD Advanced Simple Profile file
  still fails there on its video; phones' hardware decoders take it (the report's phone did).
- Tests: `AviMp3FramingTest` (JVM), and `AnAviWithMp3CutAcrossFramesPlaysTest`
  (instrumented) playing `mp3-cut-across-frames.avi`, a copyright-free file built by
  `tools/avi/unaligned-mp3-avi.py` with the reported file's chunk layout; it fails with `DECODING_FAILED` without
  the wrapper.
- Remove the wrapper if a Media3 release re-frames MP3 in AVI itself (re-run the instrumented test against it).
