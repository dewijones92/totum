---
title: Slack, Outlook and Teams stay quiet outside work hours
kind: todo
status: decided
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
