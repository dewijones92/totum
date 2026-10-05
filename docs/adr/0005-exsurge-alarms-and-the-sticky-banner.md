---
title: Exsurge wakes with exact alarms; a health service holds the step counter
kind: adr
status: accepted
updated: 2026-10-05
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
  even an ongoing notification. On 2026-10-05 he clarified that it must remain visible even
  when the feature is switched off, with Turn on, Restart clock and Summon now actions.

## Decision

- **Alarms:** one `setExactAndAllowWhileIdle` alarm at the state machine's next deadline, under
  `USE_EXACT_ALARM` (granted at install for an alarm-like app, and Totum is not on the Play Store).
  The plan said `setAlarmClock`. It was dropped because it would put an alarm icon in the status
  bar all day and replace the clock app's "next alarm" with Exsurge's.
- **Re-arming:** the alarm is re-armed after boot, an APK update and a time or zone change
  (`ExsurgeActionReceiver`), and on every process start.
- **Banner and steps:** during active hours and manual one-offs, `ExsurgeBannerService` runs as
  a `health` foreground service and holds the step-counter listener (the wake-up variant where
  the device has one). Its foreground banner uses notification ID 7303. While idle (Off, Paused
  or Dormant), the same banner content is posted plainly with ID 7301, then the service removes
  its foreground notification and stops. Starting active tracking removes the idle banner.
  Both appearances use one builder, channel, face, text and actions; the IDs separate ownership.
- **Handover (2026-10-05):** a shared ID failed the rapid Restart/Summon/Skip device regression:
  the machine was Off and the service stopped, but the notification retained an old countdown.
  Re-posting after detach and requesting immediate display alone did not fix it. Android queues
  foreground notification work, including a possible re-post during stop; it must not overwrite
  or cancel the idle banner. Separate IDs make that independent of callback timing. A delayed
  start or REST rechecks current state before keeping or stopping the service. Immediate display
  is requested for the always-visible banner; it remains low importance and silent.
- **Off is visible:** enabled controls the regular reminder schedule, not notification presence.
  An Off banner has Turn on, Summon now and Restart clock. Turn on enables the schedule; the other
  two start a manual one-off without enabling it. Existing serialized one-off state carries the
  run through alarms and process restart, then returns to Off. Explicit TurnOff cancels a live
  run; ordinary settings edits preserve a disabled manual run. The clock's existing state machine
  owns this behavior, with no second timer or service while idle.
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
