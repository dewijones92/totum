---
title: 🎥 Totum recorder — screen, mic and an optional face bubble, from one icon
kind: todo
status: built 2026-11-06, Batch A (tests owed)
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

## Built 2026-11-06 (ADR 135)

See [the feature doc](../features/totum-recorder.md) and [ADR 135](../adr/0135-totum-records-the-screen-with-voice-face-and-taps.md).
Departures from the plan above, my call: the face bubble is a service-owned overlay window with Camera2, not a
CameraX preview inside Totum (so it shows over other apps); taps are system-wide when `WRITE_SECURE_SETTINGS` is
granted by `adb`, else circles inside Totum; Totum's own sound is **not** captured separately (the microphone hears
it from the speaker), because that needs a hand-built MediaCodec/AudioPlaybackCapture pipeline that was not built
untested; the recording is a progress-strip task only for its saving step, with the running recording shown by its
notification.

## Tests owed

- `captureSize` and `recordingName` (pure): even sizes, the 1920 cap, portrait and landscape, tiny screens.
- `RecorderChoiceStore` round trip and defaults.
- `TapCircles`: a press draws a circle, a release fades it out after 450 ms, nothing draws unless the status is
  `IN_TOTUM`.
- `SystemTaps`: `show` remembers the previous value once, `restore` puts it back and forgets it, nothing happens
  without the grant.
- `RecorderService` plan: which foreground types follow from which permissions (the `Plan` rules), and that a failed
  start discards the pending file and releases the projection.
- Instrumented, on a device: consent then a 10 s recording lands in `Movies/Totum` and plays; Stop from the
  notification, the tile and the system capture chip each finish a playable file; the face bubble preview is upright
  and mirrored in portrait and landscape and is in the video; Show taps is restored after Stop and after a killed
  process; a refused microphone, camera or overlay permission records without that part and says so.
- Sweep fixes (2026-11-06), owed: `orphanVerdict` (empty, no duration, playable); `OrphanedRecordings` against a fake
  resolver (published, deleted, a recording running leaves it alone, younger than a minute is not listed);
  `SystemTaps.restoreIfLeftOn` only when a previous value is remembered; `RecorderStatus.Recording.faceLost` shown by
  `RecorderNotifications` (instrumented); a device check that a camera taken by another app removes the bubble and
  the notification says so, and that a killed recording is recovered or removed at the next launch.
