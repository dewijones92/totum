---
title: The playback service stays in the foreground for two hours after playback stops
kind: adr
status: accepted
updated: 2026-10-07
---

# 14. The playback service stays in the foreground for two hours after playback stops

- Status: Accepted
- Date: 2026-10-07

## Context

Two reports from Dewi's Pixel 7 on 0.1.573: "audio jury suddenly stopped?" and "gill gross weirdly
stopped??". Both times a downloaded file was playing in Listen mode, and the audio output went unfed for
17 s and 10 s ("the output ran dry … since it was last fed") until he pressed play again. Android's log
shows a frozen process: "Spurious audio timestamp (system clock mismatch)", and
`onTrimMemory(TRIM_MEMORY_BACKGROUND)` delivered while playing (Android refuses that level for a
foreground process: "Unable to set a background trim level on a foreground process").

The chain: Media3 leaves the foreground 10 minutes after playback stops (`DEFAULT_FOREGROUND_SERVICE_
TIMEOUT_MS`). Dewi paused for 30 minutes, resumed, and at the next item change Media3 had to start the
foreground service again from the background, which Android refused (`ForegroundServiceStartNotAllowed
Exception … mAllowStartForeground false`). With no foreground service the process was cached and frozen.
It had been hidden since early October by Exsurge's banner service, a second foreground service that kept
the process alive; Dewi turned Exsurge off at 07:38 that morning.

## Decision

The playback service stays in the foreground for **two hours** after playback stops, so a resume or an
item change after a long pause never has to start a foreground service from the background.

`setForegroundServiceTimeoutMs` cannot do it: Media3 1.10.1 clamps it to 600 000 ms (measured on the
emulator: a 2-hour setting still dropped the foreground at exactly 10 minutes). Instead the service
overrides `onUpdateNotification`, which Media3's own timeout goes through, and keeps
`startInForegroundRequired` true while `ForegroundHold` says so (an item is loaded, the player is not
stopped, and it played within two hours); a `triggerNotificationUpdate()` scheduled for the end of the
two hours lets it go. Every memory trim is logged from the service with whether it
wants to play and whether it is in the foreground; a background-level trim while it wants to play is a
warning and counts `playback.trimWhilePlaying`. Every play/pause logs the foreground state.

## Consequences

- The media notification stays (non-dismissable) for up to two hours after a pause, instead of 10 minutes.
- A report can now say whether the service was in the foreground when playback stopped.
- Not covered: a resume more than two hours after a pause, from the background, through a path Android
  does not exempt. The logging above is what will show whether that happens.
