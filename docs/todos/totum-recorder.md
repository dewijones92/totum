---
title: 🎥 Totum recorder — screen, mic and an optional face bubble, from one icon
kind: todo
status: decided
batch: A (2026-11), phase 4
priority: medium
updated: 2026-11-06
---

# 🎥 Totum recorder — screen, mic and an optional face bubble, from one icon

Dewi 2026-11-06: he wants to show a friend abroad something in Totum, recording the screen, his voice, his taps and
his face in a corner, started from a home-screen shortcut. Pixel's own Screen record can't be launched or preset by
another app (it is SystemUI's; no public intent), so Totum gets its own recorder.

Decided: a "🎥 Totum recorder" app-icon shortcut (and a Quick Settings tile) starts it; Android's MediaProjection
consent prompt appears (required for every app); recording captures the screen (Android 14+ offers "just Totum"),
the mic, and Totum's own sound (AudioPlaybackCapture for Totum's audio); a foreground-service notification with
⏹️ Stop; taps drawn as circles while Totum is on screen; a front-camera face bubble (CameraX preview overlay inside
Totum, draggable), with an on/off switch, default on. The file goes to the gallery (MediaStore Movies/Totum) and a
Share button follows. Every async step shows in the progress strip; each start/stop/failure is logged.
Previously declined the same day ("Pixel's recorder covers it"), then reinstated when Pixel's recorder turned out
not to be launchable.
