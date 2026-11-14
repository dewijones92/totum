---
title: The screen stays on for a video playing, and for a paused full-screen video only while charging
kind: adr
status: accepted
updated: 2026-11-14
---

# 161. The screen stays on for a video playing, and for a paused full-screen video only while charging

- Status: Accepted
- Date: 2026-11-13

## Context

The full player already kept the screen on while a video played (`KeepScreenOnWhilePlayingVideo`, private to
`FullPlayer.kt`), so a paused video let the screen sleep. The Shorts reel, another full-screen video surface, did not
keep it on at all. Dewi's batch brief: while a video plays full screen the screen stays on; while it is paused in full
screen it stays on only while the phone is charging.

## Decision

- One helper, `KeepScreenOnForVideo(playing, fullscreen, where)` (`ui/common/ScreenAwake.kt`), used by every
  full-screen video surface that is not the kid lock: the full player (windowed and full screen) and the Shorts reel.
- One pure rule, `screenAwakeDecision(playing, fullscreen, charging)`: playing keeps it on; paused keeps it on only in
  full screen while plugged in; every answer carries its reason.
- Charging is read from the sticky `ACTION_BATTERY_CHANGED` broadcast (`EXTRA_PLUGGED != 0`, any power source) and
  follows plug and unplug while the surface is on screen.
- Every change logs `screenawake` with the surface, the answer, the reason and the three inputs; a charging flip logs
  its plug type.
- It shares its one `view.keepScreenOn` holder with the reminder kit's `KeepScreenAwake` (2026-11-14): both call
  `HoldScreen(keepOn, tag, entered, left)`, which sets the flag, logs, and clears it when it leaves, so the flag is
  written in one place. Each caller keeps its own rule and its own log tag.
- The kid lock keeps its own rule (ADR 76: the window keeps the screen on for the whole lock, paused or not). That is a
  superset of this rule and a deliberate exception for a phone a toddler holds, so it was left alone.
- Picture-in-picture is declared in the manifest but nothing in the code enters it, so there is no surface to cover.

## Consequences

- A video paused windowed lets the screen sleep as before; paused full screen on battery now does too (it did already).
  Paused full screen on the charger no longer sleeps.
- The Shorts reel now keeps the screen on while a short plays.

## Tests

`ScreenAwakeTest` (the rule, its reasons and the plugged check). The helper itself needs a device.
