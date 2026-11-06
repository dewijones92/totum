---
title: Totum recorder
kind: feature
status: built 2026-11-06, Batch A (tests owed; not yet run on a device)
area: app
updated: 2026-11-06
---

# 🎥 Totum recorder

Records the phone's screen, with Dewi's voice, an optional face bubble and his taps shown, from an app-icon shortcut
or a Quick Settings tile. The video lands in `Movies/Totum` (the gallery) with a Share and an Open button on the
notification that follows. Decisions and reasons: [ADR 135](../adr/0135-totum-records-the-screen-with-voice-face-and-taps.md);
the original brief and the tests still owed: [the todo](../todos/totum-recorder.md).

## What it does

1. Long-press the Totum icon → **🎥 Record screen**, or add the **🎥 Record screen** tile.
2. A dialog offers 🎙️ voice, 🙂 face in a corner, 👆 show my taps (remembered), then **⏺️ Start recording**.
   Missing microphone, camera or display-over-other-apps permission is asked for or explained; the recording goes
   ahead without that part.
3. Android's screen-capture consent appears (every time; the face bubble forces "entire screen").
4. A notification shows 🔴 Recording with the elapsed time and **⏹️ Stop**. The tile and the dialog stop it too, and so
   does Android's own capture chip.
5. On stop: the file is published to the gallery, the progress strip shows "Saving", and a notification offers
   📤 Share and ▶️ Open. A failure or an empty recording says so instead.

## Seam

- `app/…/recorder/RecorderActivity.kt`: the dialog, permissions, consent, hand-over to the service.
- `RecorderService.kt`: the foreground service (`mediaProjection|microphone|camera`), the start plan, stop and save.
- `ScreenRecording.kt`: `MediaProjection` callback, `MediaRecorder`, virtual display; `captureSize` is in `RecordingFile.kt`
  with the MediaStore row (`Movies/Totum`, pending until published).
- `FaceBubble.kt`: overlay window, Camera2 front preview, drag.
- `SystemTaps.kt`: the system's Show taps, put back afterwards; `TapCircles.kt`: circles inside Totum otherwise.
- `RecorderNotifications.kt`, `RecorderTileService.kt`, `RecorderState.kt` (what the tile, notification and
  `TapCircles` read), `RecorderChoices.kt`.
- Reused: the tile pattern (`collapseAndOpen`), static shortcuts in `xml/shortcuts.xml`, the operations strip,
  `SwitchRow`, `Diag`.

## Limits, honestly

- **Show taps in every app needs a one-off grant** from a computer: `adb shell pm grant com.dewijones92.totum
  android.permission.WRITE_SECURE_SETTINGS`. Without it, taps are drawn only while Totum is on screen.
- **Totum's own sound is heard through the microphone**, not captured as its own track (ADR 135).
- **Rotation is not followed**; the recording keeps the orientation it started in.
- Not yet run on a device or emulator: the face bubble's rotation and mirroring in particular need a look.

## Diagnostics

`dewidebug` lines under the `recorder` tag: options opened and from where, permission answers, consent given or
refused, the start plan (what was asked, what was usable, overlay and camera state, taps mode), encoder, size, dpi,
bitrates, the face bubble's preview size, sensor orientation and rotation, system taps on and restored, stop reason
and duration, the file's name and byte count, and why a file was discarded.
