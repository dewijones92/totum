---
title: Boost by speech — measure (and maybe lift) only what the voice detector calls speech
kind: todo
area: playback
priority: medium
status: building 2026-11-01
updated: 2026-11-01
---

# Boost by speech

Dewi, 2026-11-01: *"we have a speech detector that's for skip silences. Why don't we use that same speech detector to
boost only the speech we detect … let's have a quick discussion on that."*

Proposed (Claude), in two steps:

1. **Speech-gated measurement**: the loudness estimate (`LoudnessBoost`, Auto and Even out) is taken only from frames
   Silero (`SpeechModel`) calls speech, so music intros and beds, applause and room tone stop moving it. One smooth
   gain as today. Low risk.
2. **Speech-gated gain** (later, capped at a few dB): lift speech more than the gaps, so pauses are not raised to
   hiss. Risk of a gate "breathing"; only after step 1 is proven.

Songs keep today's behaviour. Proof: the audio-quality suite (`RealSpeech*`) plus a new fixture of speech over a
music bed, before and after (ADR 112's method). Open question for Dewi: where does the boost bother him today
(music intros/beds, quiet guests, hiss in pauses)?

Dewi, 2026-11-01: *"maybe implement both … allow me to easily switch within settings … write feedback in the app,
like against each one in the settings … then we can prune the ones we don't like … keep an eye on the feedback, same
way you do with diagnostics."* So: both variants as choices in Settings, each with a feedback box whose text is sent
like a diagnostics report and triaged the same way; the ones he dislikes are removed afterwards.
