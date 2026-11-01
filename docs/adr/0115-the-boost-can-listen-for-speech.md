---
title: The boost can listen for speech, two ways, each with feedback
kind: adr
status: accepted
updated: 2026-11-01
---

# 115. The boost can listen for speech, two ways, each with feedback

- Status: Accepted (an experiment: the way Dewi does not like will be removed)
- Date: 2026-11-01

## Context

Dewi, 2026-11-01: *"use that same speech detector to boost only the speech we detect"*, then *"implement both,
allow me to easily switch within settings, and allow me to write feedback in the app against each one … then we can
prune the ones we don't like"*. The boost (ADR 112) measures everything it hears, so a loud music intro or bed holds a
quiet voice down, and lifting a quiet voice lifts the music under it too. The Silero speech detector (`SpeechModel`,
`SpeechTrack`) already runs for smart silence skipping.

## Decision

- **A setting with three ways** (`SpeechGate`, Settings → 🗣️ Speech-aware boost): 🔊 As before (OFF); 📏 Measure
  speech only (MEASURE: the boost learns the level from frames the detector calls speech, and lifts everything by it);
  🗣️ Lift speech only (LIFT: the same level, applied only while the detector says speech, at most +20 dB, moving at
  Even out's quick rates; music and noise stay at 1× apart from the detector's lag). It applies to Auto and Even out; Off and music stay untouched.
- **The detector runs beside the boost**: `BoostingAudioProcessor` feeds its own `SpeechTrack` on the background
  speech thread (the same one smart cutting uses) once the bundled model has loaded; `LoudnessBoost` keeps up to two
  seconds of magnitudes waiting for their verdicts, so the measurement uses only frames judged speech, and LIFT
  follows the latest verdict. No model yet: it boosts as before. Before the first verdicts (a few hundred ms after a start), MEASURE has not
  learnt a level and LIFT holds 1×; after a seek a new track starts but LIFT keeps its last verdict until the new ones
  arrive, so a voice stays lifted through a seek. LIFT also lingers for about the detector's lag after speech ends.
  Casting carries the same gate (the TV's audio is processed by the phone).
- **💬 Feedback per way** in the same settings: a short text sent as a diagnostics report whose note begins
  "💬 Feedback on the speech boost …", so it reaches the crash-log sink and is triaged like any report.
- **Logged for reports**: every minute while a gate is on, the boost line says the gate, whether it is speaking, the
  share of the last minute judged speech, and frames dropped waiting for a verdict.

## Consequences

- Measured on real speech (the LibriVox clip, `RealSpeechOverMusicTest`, 8 s of loud chords then the voice 12 dB down
  with a bed 10 dB under it): LIFT left the bed at 1.00× and lifted the voice section 8.2×; MEASURE was 3.5× louder
  than today in the first seconds after the loud intro (2485 vs 701 RMS) and 1.7× later. The whole audio-quality suite
  (35 tests) still passes. With the voice 10 dB *under* the bed, Silero hears no speech and both ways fall back to
  doing nothing; that is the detector's limit, not a bug.
- LIFT lags the start of a voice by the detector's look-ahead (a fraction of a second) and may sound like a swell; the
  feedback will say.
- A second speech detector runs while both smart cutting and a gate are on; its cost per chunk is not yet measured on a phone (smart cutting logs its own `microsPerChunk`).
