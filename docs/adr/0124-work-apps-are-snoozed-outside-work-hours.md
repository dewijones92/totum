---
title: Work apps are snoozed outside work hours, by a notification listener
kind: adr
status: accepted
updated: 2026-11-06
---

# 124. Work apps are snoozed outside work hours, by a notification listener

- Status: Accepted, amended 2026-11-06 (groups of apps, each with its own hours; a notification at each change)
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
  ongoing, category call or alarm, the app's group paused, inside the group's window → let through; otherwise snooze.
  With no day set in a group's window, its notifications are snoozed for 24 h at a time rather than let through.
- **The user grants Notification access once.** The page shows ✅ / ⚠️ and opens
  `ACTION_NOTIFICATION_LISTENER_SETTINGS`. The feature ships **off**, and a missing grant shows as a ⚠️ line only
  once the switch is on, so it never nags someone who has not asked for it.
- **Status is one line on the alarm board.** `AlarmBoard` carries an optional `WorkAppsStatus` (needs access, or one
  `GroupState` per group: quiet until, on until, paused until); the board notification shows it as its sub-text (and
  as its text when no alarm is set), and the status's `refreshAt` (the earliest group boundary) joins the board's own
  `refreshAt`, so the existing board refresh redraws it at every group's boundaries. Settings and access changes call
  `DailyAlarmController.tickAll`. The controller takes the status through a `() -> WorkAppsStatus?`, so `:lib:dailyalarms`
  stays free of Android.
- **Settings:** per group: a name, an emoji, per-day hours (a day off is quiet all day), an app list (installed
  launchable apps that declare the notification permission, every app on request, or a typed package name) and a
  pause (1 h, 3 h, until the next hours start). Stored as JSON in its own prefs file (`work_apps_quiet`).
- **Counted logs:** each snooze and let-through for a chosen app is tallied, and a line is written at the first of
  each kind and every tenth; reports carry the tallies and the last decision.

## Consequences

- A split second of sound can still leak before the snooze; the page says to turn those apps' own sound off.
- Snoozed notifications return at the window start, all at once; the status line says when.
- Notifications already in the shade at 17:30 are not touched. Ones snoozed before a pause began are handed back
  (amended below).
- The manifest gains one service (`BIND_NOTIFICATION_LISTENER_SERVICE`) and one activity; no new `uses-permission`.
  The launcher-intent `<queries>` already present is what makes the app picker and the board's app names work.
- Unverified until a phone run: that the sub-text shows on the Android 16 promoted board, and whether the restricted
  settings step is needed for an Obtainium install.
- The bedtime sleep timer reuses `WeeklyWindow` instead of writing a third window.

## Amendment 2026-11-06: groups, and a notification at each change

Dewi, 2026-11-06 (Batch A phase 4): "groups of apps, each with its own schedule" (💼 Work: Slack, Teams, Outlook,
Mon-Fri 09:00-17:30; 📱 Social: quiet after 22:00) and "a notification each time a group's quiet state changes".
Icons for the apps in the board line were judged not feasible in the Live Update style (text only), so each group's
emoji stands in.

- **The model is a list of `QuietGroup`** (`id`, `name`, `emoji`, `packages`, a `WeeklyWindow`, `pausedUntil`) in
  `WorkAppsSettings`; the master switch stays global. An app belongs to one group (adding it to a group takes it out of
  any other; `validated()` keeps the first claim). The pause moved from the whole feature to each group, since being on
  call for work should not let Social through. `QuietGroup.state(now, zone)` gives a `GroupState` (`QUIET`, `ON`,
  `PAUSED`, and the time it runs until); `WorkAppsStatus.Groups` carries one per group, and the Quiet-hours mode chip
  shows when any group is quiet.
- **Migration:** a stored single list (`packages`, `window`, `pausedUntil` and no `groups` key) is read by
  `WorkAppsCodec.decode` as one default group, 💼 "Work apps", carrying the same apps, window and pause, and is saved in
  the new shape at once (logged as a migration). Nothing is lost; a fresh install gets the same default group.
- **The board line is one combined line**, one short segment per group joined with " · ": `💼 Work apps 🔕 quiet until
  09:00 Mon · 📱 Social 🔔 on until 22:00` (`⏸️ let through until` while paused). My call over one line per group: the
  board's sub-text is a single line, and a group name and emoji say who each segment is about.
- **A notification each time a group changes state**, from the same boundaries the board already uses. `WorkAppsQuiet.
  noteBoundaries` runs at the end of every `DailyAlarmController.tickAll` (the board refresh alarm, startup, and any
  tick), compares each group's state with the last one it recorded (a persisted map, so a restart does not forget), and
  announces a move into quiet or back on: "🔕 Work apps quiet until 09:00 Mon" and "🔔 Work apps back on", with the
  apps (or the next quiet time) as the body. Silent by rule, and logged with the reason: the first reading of a group,
  any change made from the settings page (the person just did it), a pause starting, and a group with no days set.
  The pause ending by itself is announced. The channel is its own, "Quiet hours: changes", low importance and silent.
- **Removing a group, or an app from a group, can be taken back** through the shared undo helper (ADR 128), at its old
  slot.

Consequences: a notification arrives late if the phone was off at a boundary (it is sent when the state is next read
and says the state then). Unverified until a phone run, as before.

## Amendment 2026-11-06: findings from the use-case sweep

- **An app that stops being quiet gets back what was snoozed.** `WorkAppsQuiet` works out which chosen apps are quiet
  now (the same `decide` a posted notification gets) and, on every settings edit and every boundary tick, emits
  `quietEnded` for the apps that were quiet and no longer are (a pause, a group or the switch turned off, an app or a
  group removed, hours starting). The listener hands back what it snoozed for them. Android's public listener API has no
  unsnooze (`snoozeNotification` and `getSnoozedNotifications` only), so it re-snoozes each key for one second and logs
  how many were still snoozed 5 s later. The keys it snoozed are kept in their own prefs file so the listener never
  touches a snooze the person made, and it also reconciles when it connects. Unverified on a phone: that the platform
  accepts a re-snooze of an already snoozed key.
- **An edit announces only what the edit did not change.** The edit runs the boundary check with the edited groups
  silent (or all of them when the switch moved), instead of remembering every group's new state and losing a boundary
  an untouched group had crossed.
- **A change notice that cannot be shown is recorded.** Without POST_NOTIFICATIONS the notice is not retried (the
  change stays consumed, so no retry storm), but it is counted and logged with its reason (`notifications not allowed`)
  and carried in reports (`workApps.noticesNotShown`, `.lastNoticeNotShown`).
