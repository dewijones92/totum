---
title: PlaybackService refused a foreground start from the background (two field crashes)
kind: todo
status: fixed 2026-10-01 — mechanism traced in Media3 1.10.1, refusal now logged instead of crashing
area: playback
priority: high
requested: 2026-10-01
updated: 2026-10-01
---

# PlaybackService refused a foreground start from the background

Two crash reports from Dewi's Pixel 7 (Android 16, API 36), both on 0.1.548 (`df8d89d`, release):

| Report (on the Pi) | Exception |
|---|---|
| `2026-09-29/20260929T152336-107bf2bd.json` | `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false: service com.dewijones92.totum/.playback.PlaybackService` |
| `2026-09-30/20260930T162133-562faa95.json` | the same |

The stack holds only framework frames (the throw is unmarshalled from a Binder parcel on `main`), so
it does not say which call started the service. Crash reports carry no event trail, so the trigger
is not in the report either. **That gap in the report is part of this item.**

## Why it matters beyond playback

Exsurge et Disce's end of break resumes playback from an exact-alarm tick while the app is in the
background (`ExsurgeEffect.ResumePlayback` → `PlaybackController.setPlaying(true)`). That is the
kind of start that can be refused. The exact alarm's temporary allowlist should cover it, but this
is unverified on a real phone.

## Cause (traced 2026-10-01; the second Opus review reached the same conclusion independently)

Media3 1.10.1 (`MediaNotificationManager`, read from bytecode) has two routes to `startForeground`:

- **The direct update**, `lambda$updateNotification$6`. It catches `IllegalStateException`, and the
  refusal is one. It goes to `onForegroundServiceStartNotAllowedException` and is logged.
- **The provider's late callback**, `onNotificationChanged` → `onNotificationUpdated` →
  `updateNotificationInternal` → `startForeground`. Artwork that finishes loading in the background
  takes this route, run synchronously on the main executor. It **catches nothing**.

The field stack has that shape: a `Handler` callback calls an executor, which calls
`startForegroundService`.

The trigger for #6 was the queue advancing. The diagnostics report sent 10 minutes later ("the last
one didn't auto play") has a trail that begins two seconds after the crash, so that missing
autoplay was this crash.

## Fixed

`PlaybackService` installs `RefusalTolerantNotificationProvider` around Media3's own
`DefaultMediaNotificationProvider`. It wraps the late callback and logs a refusal
(`dewidebug a late notification update needed a foreground start the system refused`) instead of
crashing; anything else still surfaces. Device test: `ARefusedForegroundStartDoesNotCrashTest`.

## Still worth doing

- Map the frames with `totum-mapping.txt` from the v0.1.548 release, and add a breadcrumb trail to
  crash reports.
- Find which path calls `play()` / `prepare()` while the app is in the background.
- Catch `ForegroundServiceStartNotAllowedException` where playback is started without the user
  being present, and log the refusal instead of crashing.
