---
title: The Pi runs the phone's sound processing
kind: adr
status: accepted
updated: 2026-11-07
---

# 153. The Pi runs the phone's sound processing

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-06 (option B): sound on a Pi-fed target is exactly the phone's processing, one engine, not a second copy.
[ADR 119](0119-cast-and-sound-code-shared-with-the-pi.md) moved the processing into `:core:sound`, pure JVM, and the
spike measured the whole chain at about 13 times real time on one of the Pi's four cores.

## Decision

- **One PCM chain.** `PcmChain` (`:core:sound`) is the cutter and then the boost on interleaved 16-bit samples, built
  from the same `SilenceCutter` (with `SpeechTrack` when the speech weights load) and `LoudnessBoost` the Media3
  processors use. `process` and `finish` return what to play; `skippedFrames` says what was taken out. Chunking does not
  change the output, and with nothing switched on it is sample for sample the input (both tested).
- **ffmpeg only moves bytes.** On the Pi `PcmPump` (`cli/serve/sound`) reads ffmpeg's decode (`-vn -ac 2 -ar 48000 -f
  s16le`, with `-ss` before `-i` to start part-way) through the chain. `ProcessedSound` builds the two pipelines:
  - **to a speaker:** the processed PCM is written straight into mpv's stdin (`--demuxer=rawaudio`), no re-encode;
  - **to a TV:** the processed PCM goes to an ffmpeg AAC encoder and is served as an ADTS stream by `CastHttpServer`
    from `:core:cast` (`SoundStreams`, a per-offer random token in the path, withdrawn when the item ends).
- **Position is the source's.** A processed item's position is mpv's (or the TV's) time plus where it started plus the
  silence taken out, so the number the phone shows and YouTube is told is a place in the original. A processed item has
  no duration from the player; the item's own is used.
- **Changing the sound restarts the item where it was**, on the processed path or off it.
- **A picture is not processed yet.** For a TV showing a video, the sound goes as it comes; the log line says so. The
  phone's DASH path (original video packets plus processed audio segments, ADR 68) needs `CastStreams` split in two
  and is not built.
- **TVs are driven by `pychromecast`** in a small helper (`server/totum-cli/chromecast_helper.py`) speaking JSON lines
  over stdin and stdout (`CastWire`); the TVs are configured by address (`TOTUM_CAST_TARGETS`), not discovered, because
  mDNS does not cross the container's bridge network.

## Consequences

- Nothing here has run against a TV, a speaker or Chromecast: the unit tests cover the chain, the pump, the wire
  format, the deck and the reporting rule. The first daytime run on the real devices is the test of the rest.
- ffmpeg is a hard dependency of a processed play, and a missing or failing one ends the item as failed (logged with its
  exit code) rather than silently skipping.
- The Pi spends about 1/13 of a core per processed stream (the 2026-11-06 spike), so several streams fit at once.
- Playback speed is not carried to the Pi yet; a processed or direct stream plays at 1x.
