---
title: 🎥 Totum recorder — screen, mic and an optional face bubble, from one icon
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11), phase 4
priority: medium
updated: 2026-11-07
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

## Built 2026-11-06 (ADR 135)

See [the feature doc](../features/totum-recorder.md) and [ADR 135](../adr/0135-totum-records-the-screen-with-voice-face-and-taps.md).
Departures from the plan above, my call: the face bubble is a service-owned overlay window with Camera2, not a
CameraX preview inside Totum (so it shows over other apps); taps are system-wide when `WRITE_SECURE_SETTINGS` is
granted by `adb`, else circles inside Totum; Totum's own sound is **not** captured separately (the microphone hears
it from the speaker), because that needs a hand-built MediaCodec/AudioPlaybackCapture pipeline that was not built
untested; the recording is a progress-strip task only for its saving step, with the running recording shown by its
notification.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`RecorderPureRulesTest` (`:app`, 11: `captureSize` for tall, landscape, within the cap, odd, tiny, another cap and a
sweep of real screen sizes; `recordingName`; `orphanVerdict` for empty, no duration and playable), instrumented
`BatchAStoresTest` (`RecorderChoiceStore` round trip and defaults, restoring the choices). Seen failing with the cap
changed and with an empty file no longer deleted.

Still owed, with the reason: `TapCircles` (needs `RecorderState` set and real touches), `SystemTaps` (needs
`WRITE_SECURE_SETTINGS`, granted by `adb`), `OrphanedRecordings` against a resolver (MediaStore, device), the `Plan`
foreground-type rules (a private class inside `RecorderService`; it needs exposing as a pure function to be unit tested),
`RecorderStatus.Recording.faceLost` in the notification, and every device check listed above.
