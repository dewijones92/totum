---
title: Slack, Outlook and Teams stay quiet outside work hours
kind: todo
status: built 2026-11-06, Batch A (tests owed); groups and change notifications built 2026-11-06 too
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Slack, Outlook and Teams stay quiet outside work hours

Dewi 2026-11-05/06: silence them OUTSIDE 09:00-17:30 on weekdays, all day at weekends, "sensibly configurable",
shown as one line on the always-pinned alarms board. A `NotificationListenerService` (notification access granted
once) snoozes their notifications until the window ends; a split-second sound can still leak, so the screen suggests
turning those apps' sound off. Configurable per day, editable app list, a pause switch. Reuse `AlarmBoardService`,
`DailyAlarmNotifications.board()` (BOARD_ID 7398), `NextTime.nextOccurrence`. None exists today.

Whole-batch review: the window is a per-day `WeeklyWindow` extracted from `ExsurgeSettings.isActiveAt/nextActiveStart` into `:lib:reminders`, shared with the bedtime timer.

## Built 2026-11-06

Done as [features/work-apps-quiet-hours](../features/work-apps-quiet-hours.md), decided in
[ADR 124](../adr/0124-work-apps-are-snoozed-outside-work-hours.md). Dewi's batch rule: code first, so the tests are
listed in the feature doc under "Tests still to write". Not yet seen on a phone: the board sub-text on Android 16, and
whether an Obtainium install needs "Allow restricted settings" before Notification access can be switched on.

## Groups and change notifications (Dewi, 2026-11-06, Batch A phase 4) — built 2026-11-06

- **Groups of apps, each with its own schedule**: e.g. 💼 Work (Slack, Teams, Outlook; Mon-Fri 09:00-17:30) and
  📱 Social (WhatsApp, Instagram; quiet after 22:00). Each group has a name, an emoji, its apps and its own weekly
  window (the shared `WeeklyWindow`); the board line lists each group's state.
- **A notification at each change**: "💼 09:00: Slack, Teams and Outlook notifications are on" and "🔕 17:30: they're
  off until 09:00 Mon", from the same boundary times the board already uses.
- App icons in the board line are not possible in the Live Update style (text only); each group's emoji stands in.

Done as an amendment to [ADR 124](../adr/0124-work-apps-are-snoozed-outside-work-hours.md) and
[features/work-apps-quiet-hours](../features/work-apps-quiet-hours.md). The single list is migrated into a default
💼 "Work apps" group, so nothing is lost. My calls (not Dewi's): the board line is one combined line (a segment per
group); the pause is per group; an app belongs to one group; the notification wording is "🔕 Work apps quiet until
09:00 Mon" / "🔔 Work apps back on", and a change made from the settings page does not announce itself.

## Tests owed

Beyond the first-build list in the feature doc ("Tests still to write"):

- `WorkAppsSettingsTest`: groups (`groupOf`, an app moves between groups, `validated` keeps the first claim and drops
  duplicate ids), per-group `decide` (each group's own window, pause and snooze return time), `status` with several
  groups and `refreshAt` as the earliest boundary, `GroupState.changedFrom` (first reading, into quiet, back on, into a
  pause, no days set).
- `WorkAppsCodecTest`: a stored single list decodes as one default group with the same apps, window and pause; the new
  shape round-trips; `encodeKinds` and `decodeKinds`.
- `WorkAppsQuiet` hub tests: `noteBoundaries` announces a time-driven change once, never the first reading or a change
  made through `update`, and a pause ending by itself is announced; `removeGroup` and `restoreGroup`, `removeApp` and
  `restoreApp` keep their slot.
- A device check of the page with two groups, the combined board line and the notification.
