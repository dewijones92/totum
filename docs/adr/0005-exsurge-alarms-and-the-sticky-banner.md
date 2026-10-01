---
title: Exsurge wakes with exact alarms; a health service holds the step counter
kind: adr
status: accepted
updated: 2026-10-01
---

# 5. Exsurge wakes with exact alarms, and a health foreground service holds the step counter

- Status: Accepted
- Date: 2026-10-01

## Context

- WorkManager runs no more often than every 15 minutes, and inexactly, so it cannot do a 60-second
  escalation or a five-minute break.
- `TYPE_STEP_COUNTER` only counts while something is listening, so the sitting clock needs a
  listener alive through the whole 30 minutes, not just during the break.
- Dewi asked for the banner to be *"always sticky and there"*. Android 14 lets the user swipe away
  even an ongoing notification.

## Decision

- **Alarms:** one `setExactAndAllowWhileIdle` alarm at the state machine's next deadline, under
  `USE_EXACT_ALARM` (granted at install for an alarm-like app, and Totum is not on the Play Store).
  The plan said `setAlarmClock`. It was dropped because it would put an alarm icon in the status
  bar all day and replace the clock app's "next alarm" with Exsurge's.
- **Re-arming:** the alarm is re-armed after boot, an APK update and a time or zone change
  (`ExsurgeActionReceiver`), and on every process start.
- **Banner and steps:** during active hours, `ExsurgeBannerService` runs as a `health` foreground
  service. Its ongoing notification is the banner, and it holds the step-counter listener (the
  wake-up variant where the device has one). Outside active hours the service is sent a `REST`
  action and detaches its own notification (`stopForeground(STOP_FOREGROUND_DETACH)`, then
  `stopSelf`), so the same notification stays as a plain ongoing one.
  - It was `stopService` plus a detach in `onDestroy` until the 2026-10-01 review. By `onDestroy`
    the system has already dropped the foreground record and queued a cancel of the notification,
    which races the re-post.
- **Steps before a tick:** before each alarm tick the step sensor is flushed and the tick waits
  750 ms. Otherwise, steps walked with the screen off can sit in the hardware buffer until after
  the summons they should have prevented.
- **Re-arming:** the alarm is re-armed even when the deadline is unchanged, if that deadline has
  passed. The old "unchanged, skip" check could leave nothing scheduled.
- **Sticky:** the banner's delete intent re-posts it the moment it is swiped, the same technique as
  Loquax's `BannerManager`.
- **No permission:** without the Physical activity grant, the service is not started, the banner
  is posted plainly, steps are not counted, and GO starts the break straight away.

## Consequences

- An always-on notification, by request. It doubles as the countdown, and the next summons time is
  on it.
- A foreground-service start from the background relies on the exact-alarm exemption. A refusal is
  logged loudly and falls back to the plain banner.
