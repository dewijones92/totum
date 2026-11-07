---
title: The cast and sound code lives in two JVM modules the Pi shares
kind: adr
status: accepted
updated: 2026-11-06
---

# 119. The cast and sound code lives in two JVM modules the Pi shares

- Status: Accepted
- Date: 2026-11-06

## Context

The Pi is to play to TVs, browsers and speakers with the phone's own processing
([the Pi plays everywhere](../todos/pi-plays-everywhere.md)): the same stream builder that serves a TV from the
phone, and the same skip-silence and boost, so one sound engine serves both. Both were inside `:core:playback`, an
Android library, so the Pi's `:cli` could not use them. About two thirds of the 41 cast files and all of the sound
processing (`SilenceCutter`, `SpeechModel`, `SpeechTrack`, `LoudnessBoost`, `VolumeBoost`, `LookaheadLimiter`) had no
Android imports; the audio-quality tests already ran them on the JVM, fed by ffmpeg.

## Decision

- **`:core:cast`**, pure JVM with `explicitApi()`: the plain-Kotlin cast files (manifest, relay and rung choice,
  container layouts and remuxers, the cast HTTP server, reach and timeline, `ScreenLink`, captions, probes, address),
  `CastProblem`, `CastStage` and the TV port (`CastTv`, `CastTvPort`, split out of `CastStatus`).
- **`:core:sound`**, pure JVM with `explicitApi()`: `SilenceCutter`, `SpeechModel` (`SpeechWeights`, `SpeechWorker`),
  `SpeechTrack`, `LoudnessBoost`, `VolumeBoost` and its store port, `LookaheadLimiter`.
- **Packages are unchanged**, so no import elsewhere moved. `:core:playback` depends on both with `api`.
- **The Media3 wrappers stay in `:core:playback`** (`SilenceCuttingAudioProcessor`, `BoostingAudioProcessor`, the
  chains, sinks, `AudioChain`, `TailScan`), as does everything that touches `MediaCodec`, Android or Media3 in the cast
  package (`CastStreams`, `CastHost`, `AudioTranscode`, `CastStatus`, `ScreenPlayer` and the rest).
- **The Silero weights exist once**, as `silero_vad.bin` in `:core:sound`'s Java resources, loaded from the classpath
  by `SpeechWeights.bundled()` for the phone (`BundledSpeechModel`), the unit tests and the audio-quality suite.
  Java resources of a dependency go into the APK, so nothing else packages it.
- Only declarations the remaining `:core:playback` or `:app` code uses became public; the rest stayed `internal`.
- Behaviour is unchanged: this change only moves code.

## Consequences

- The Pi's `:cli` can depend on these modules and run the phone's stream building and sound processing, with no second
  copy to keep in step.
- The audio-quality suite is now `:core:sound:test -Ptotum.audioQualityTests`, and its workflow
  (`audio-quality.yml`) and the preflight check follow the new paths. It reads the two test clips from
  `core/playback/src/main/res/raw`, which stay where the preview feature uses them.
- `:core:cast` was Kover-exempt at first: the moved tests covered 65.2% of its lines against the 75% bar for logic
  modules (`CastHttpServer`, `Upstream`, `TvVideos`, `CastTimeline`, `WebmLayout`, `LocalRanges` and `Fmp4Aac` were the
  gaps). **Lifted 2026-11-07:** those gaps now have tests, the module covers 87.5%, and it is held by `koverVerify` like
  `:core:sound`.
- `CastStreams` was split on 2026-11-07 ([ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)): `CastStreamServer`, `ProcessedAudio`, `LiveRelay`/`LiveCasts`, `LocalManifests`, `VideoRemux` and `EmbeddedSubtitles` moved here behind `AudioEngine`, `CastHostPort` and `MediaTools`; `CastStreams` in `:core:playback` is an adapter.
- **`ChunkedRead` moved to `:core:cast` on 2026-11-07** (package `com.dewijones92.totum.playback` unchanged, now public, its
  test with it) so the Pi's YouTube relay (`ChunkedUpstream`, [ADR 153](0153-the-pi-runs-the-phones-sound-processing.md))
  and the phone's `ChunkedDataSource` share one ranged-read state machine instead of a second copy.
