---
title: Totum records the screen, with voice, a face bubble and shown taps
kind: adr
status: accepted
updated: 2026-11-06
---

# 135. Totum records the screen, with voice, a face bubble and shown taps

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06: he wants to show a friend something in Totum, and to record bug reports and demos, with the
screen, his voice, his taps and his face in a corner, started from a home-screen icon. The Pixel's own Screen record
is SystemUI's and has no public intent, so nothing can launch or preset it. The first design
([totum-recorder](../todos/totum-recorder.md)) kept the face bubble and the taps inside Totum, as a CameraX preview
and a pointer layer in the app's own window.

## Decision

- **One entry, three ways in:** an app-icon shortcut (`record_screen`) and a Quick Settings tile open
  `RecorderActivity`, a translucent dialog with three switches (🎙️ voice, 🙂 face, 👆 taps, remembered) and
  **Start recording**. While recording, the same entries show **Stop**, and the tile stops it directly.
- **Android's consent is asked every time.** `MediaProjection` tokens are single use and the dialog cannot be
  skipped. On Android 14+ the intent is `createConfigForDefaultDisplay()` when the face bubble is on, because a
  one-app share would leave out the bubble's overlay window, and `createConfigForUserChoice()` otherwise.
- **One foreground service, `RecorderService`**, typed `mediaProjection` plus `microphone` and `camera` only when
  those are in use and permitted (a type without its permission is a `SecurityException`). It starts the foreground
  first, then takes the projection, then registers the `MediaProjection.Callback` before `createVirtualDisplay`
  (Android 14 requires both orders). Stopping from the system's own capture chip arrives through that callback.
- **`MediaRecorder` does the encoding** (H.264 from the virtual display's surface, AAC from the microphone, MP4,
  30 fps, 8 Mbps, long side capped at 1920 and sizes made even), straight into a pending **MediaStore** row in
  `Movies/Totum`, so no storage permission is needed and the file is in the gallery. It is published only when the
  encoder stopped cleanly and the file is not empty; otherwise the row is deleted.
- **Visible, always:** an ongoing notification with a chronometer and ⏹️ Stop; when it finishes, a notification
  with 📤 Share and ▶️ Open (or ❌ with the reason). The save is a progress-strip task. Every decision and failure is a
  `dewidebug [recorder]` line: consent, plan, encoder and sizes, camera, taps, stop reason, file and bytes.
- **The face bubble is a system overlay window owned by the service**, not a Compose layer in Totum. It is a
  `TextureView` fed by Camera2 (the front camera, mirrored, cropped to a circle, draggable), added with
  `TYPE_APPLICATION_OVERLAY`, so it shows over every app and is captured by the full-screen share. It needs the
  "display over other apps" permission (already declared for Exsurge) and the camera permission; without either, the
  recording goes ahead without it and says so. The camera-typed foreground service is what lets the camera keep
  running while Totum is in the background.
- **Taps:** with `WRITE_SECURE_SETTINGS` granted (once, from a computer: `adb shell pm grant com.dewijones92.totum
  android.permission.WRITE_SECURE_SETTINGS`), the service turns the system's own Show taps on for the recording and
  puts the previous value back, remembering it on disk so a killed process restores it next time. Without that grant
  there is no way for an app to see touches in another app, so taps are drawn as circles by `TapCircles` while Totum
  is on screen. The two are never on together.
- **Rotation is not followed.** The encoder's surface is fixed at the start orientation; a phone turned mid-recording
  is scaled into it.

## Consequences

- Departures from the first design, my call, each for the same reason (the recorder is for other apps as well as
  Totum): the face bubble is an overlay rather than CameraX inside Totum, which also avoids a CameraX dependency and
  the app-size cost; taps can be system-wide when the one-off grant is given.
- **Totum's own sound is not captured as a separate track.** The microphone hears it from the speaker. Capturing it
  cleanly means replacing `MediaRecorder` with a hand-built `MediaCodec` + `AudioPlaybackCapture` + mixer + muxer
  pipeline; that was not built untested, and is the follow-up if the speaker pickup is not good enough.
- A permission list grew: `RECORD_AUDIO`, `CAMERA`, three `FOREGROUND_SERVICE_*` types, `WRITE_SECURE_SETTINGS`
  (only ever granted by `adb`; lint's `ProtectedPermissions` is ignored on it for that reason).
- The recorder cannot start from nothing: `startForeground` with microphone or camera types must happen from a
  visible activity, which is why the dialog always comes first and the tile opens it rather than starting directly.
