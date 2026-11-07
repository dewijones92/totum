---
title: Slack, Outlook and Teams stay quiet outside work hours
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07); groups and change notifications built 2026-11-06 too
batch: A (2026-11)
priority: high
updated: 2026-11-07
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

## Tests (Batch A testing stretch, part A, 2026-11-07)

`WorkAppsSettingsTest` (`:lib:dailyalarms`, 20: every `decide` branch and its reason, the snooze return time with its
margin, the minute at each edge, a pause, no days set, per-day hours, `status` and `refreshAt` as the earliest boundary,
group state, `validated` keeping the first claim and dropping duplicate ids, clamped hours, `withApp`, `withGroup`,
`withoutGroup`, labels, the codec, the tally), `WorkAppsQuietHubTest` (`:app`, 18: a boundary announced once and never
on the first reading, remembered across a restart, a change made on the page silent, a pause ending by itself
announced, an edit not swallowing another group's boundary, the whole switch silent, a refused notice counted and not
retried, `quietEnded` for a pause / switch off / group deleted / app removed / the day beginning and nothing for apps
that stay quiet, remove and restore keeping their slot, pausing until the next start, the report), with
`WorkAppsGroupsTest`, `WeeklyWindowTest`; instrumented `BatchAStoresTest` (`SnoozedKeys` add, contains, remove). Seen
failing with the edited-group rule removed and with calls and alarms no longer exempt.

Still owed: `AlarmBoardTest` for the `workApps` refresh joining `refreshAt`; the listener against a posted
notification and that re-snoozing for one second brings a snoozed one back (device); the page with two groups, the
combined board line and the notification (device). `SnoozedKeys.keepOnly` is deliberately not run on a device: it
would forget the real snoozed notifications.
