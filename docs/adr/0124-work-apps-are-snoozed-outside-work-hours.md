---
title: Work apps are snoozed outside work hours, by a notification listener
kind: adr
status: accepted
updated: 2026-11-06
---

# 124. Work apps are snoozed outside work hours, by a notification listener

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi wants Slack, Outlook and Teams silent outside 09:00–17:30 on weekdays and all day at weekends, configurable, with
the state shown as one line on the always-pinned alarms board. Android has no API to silence another app's channels,
and Do Not Disturb schedules silence everything, not three apps. The one supported lever that works per app is the
notification listener: it sees each notification the moment it is posted and can **snooze** it until a time we choose.

The window is also what the bedtime timer will need, and Exsurge already owned the only weekly-window maths in the
app, with one start and end for all days.

## Decision

- **A per-day `WeeklyWindow` lives in `:lib:reminders`** (`WeeklyWindow`, `DayHours`): which days, a start and an end
  for each, "inside at instant X in zone Z", the next start, the end of the run (through midnight when one day ends
  at 24:00 and the next starts at 00:00) and the next boundary. `ExsurgeSettings.isActiveAt`, `nextActiveStart` and
  `activeEndAfter` keep their signatures and answer through a uniform window, so Exsurge's behaviour is unchanged
  (its 119 tests pass); one difference by construction: `activeEndAfter` on a day not in the window returns the
  instant itself where it used to return that day's end, a case Exsurge never reaches. `nextOccurrence` is the
  per-day time helper `nextStart` is built on.
- **`WorkAppsListenerService` snoozes outside the window** with `snoozeNotification(key, ms)`, for the chosen apps
  only, until the window's next start plus 5 s (so a notification handed back a moment early is not snoozed again).
  The decision is one pure function, `WorkAppsSettings.decide`, in `:lib:dailyalarms`: switched off, not a chosen app,
  ongoing, category call or alarm, paused, inside the window → let through; otherwise snooze. With no work day set at
  all, a notification is snoozed for 24 h at a time rather than let through.
- **The user grants Notification access once.** The page shows ✅ / ⚠️ and opens
  `ACTION_NOTIFICATION_LISTENER_SETTINGS`. The feature ships **off**, and a missing grant shows as a ⚠️ line only
  once the switch is on, so it never nags someone who has not asked for it.
- **Status is one line on the alarm board, not a notification of its own.** `AlarmBoard` carries an optional
  `WorkAppsStatus` (quiet until, on until, paused until, needs access); the board notification shows it as its
  sub-text (and as its text when no alarm is set), and the status's `refreshAt` joins the board's own `refreshAt`, so
  the existing board refresh redraws it at 09:00 and 17:30. Settings and access changes call
  `DailyAlarmController.tickAll`. The controller takes the status through a `() -> WorkAppsStatus?`, so `:lib:dailyalarms`
  stays free of Android.
- **Settings:** per-day hours (a day off is quiet all day), an app list (installed launchable apps that declare the
  notification permission, every app on request, or a typed package name) and a pause (1 h, 3 h, until the next work
  day). Stored as JSON in its own prefs file (`work_apps_quiet`).
- **Counted logs:** each snooze and let-through for a chosen app is tallied, and a line is written at the first of
  each kind and every tenth; reports carry the tallies and the last decision.

## Consequences

- A split second of sound can still leak before the snooze; the page says to turn those apps' own sound off.
- Snoozed notifications return at the window start, all at once; the status line says when.
- Notifications already in the shade at 17:30, and ones snoozed before a pause began, are not touched.
- The manifest gains one service (`BIND_NOTIFICATION_LISTENER_SERVICE`) and one activity; no new `uses-permission`.
  The launcher-intent `<queries>` already present is what makes the app picker and the board's app names work.
- Unverified until a phone run: that the sub-text shows on the Android 16 promoted board, and whether the restricted
  settings step is needed for an Obtainium install.
- The bedtime sleep timer reuses `WeeklyWindow` instead of writing a third window.
