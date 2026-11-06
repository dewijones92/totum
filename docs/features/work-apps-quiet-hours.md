---
title: Work apps quiet hours — groups of apps stay silent outside their own hours
kind: feature
area: side-quest
status: built 2026-11-06, Batch A (tests owed) — ships switched off; groups of apps and change notifications built the same day
updated: 2026-11-06
---

# 🔕 Work apps quiet hours

**Ask (Dewi, 2026-11-05/06):** silence Slack, Outlook and Teams outside 09:00–17:30 on weekdays and all day at
weekends, "sensibly configurable", shown as one line on the always-pinned alarms board; then (2026-11-06) groups of apps
each with their own schedule, and a notification each time a group's state changes. Decision:
[ADR 124](../adr/0124-work-apps-are-snoozed-outside-work-hours.md) (amended for groups); backlog:
[todos/work-apps-quiet-hours](../todos/work-apps-quiet-hours.md).

## What it does

- A `NotificationListenerService` (`WorkAppsListenerService`) sees every notification the chosen apps post. **Outside
  their group's hours** it snoozes each one (`snoozeNotification`) until the hours start, so Monday's 09:00 brings back
  what arrived over the weekend. Inside the hours, or when the group is paused, or for an app that is in no group,
  nothing happens.
- **Groups.** Apps live in groups, each with a name, an emoji, its own hours and its own pause: 💼 Work (Slack,
  Outlook, Teams, Mon–Fri 09:00–17:30) is there from the start, and **➕ Add a group** makes another (say 📱 Social,
  quiet outside 08:00–22:00). An app is in one group at a time. The old single list became the 💼 Work group.
- **A notification each time a group changes state**: "🔕 Work apps quiet until 09:00 Mon" at 17:30 and "🔔 Work apps
  back on" at 09:00 (its body names the apps, or when the group goes quiet again). Silent, on its own channel "Quiet
  hours: changes". Not sent for the first time a group is read, for anything you change on the settings page, or when
  you start a pause; a pause running out by itself is announced.
- Never snoozed, whatever the hour: **ongoing** notifications (a Teams call in progress, a foreground service) and
  notifications whose category is **call** or **alarm**.
- Android asks you **once** for *Notification access*. The page shows ✅ granted / ⚠️ not granted, explains why in
  two lines, and opens the system page (`ACTION_NOTIFICATION_LISTENER_SETTINGS`). If Android greys the switch out (a
  sideloaded build), the page says where "Allow restricted settings" is.
- It ships **off**. Turning the switch on with access missing opens the system page straight away.

## Settings (page "🔕 Work apps quiet hours")

Reached from **Settings → 🔕 Work apps quiet hours** and from the bottom of the **Alarms** page; both open the same
screen, in the side-quest look ([ADR 34](../adr/0034-the-side-quests-look-roman-and-bold.md)).

| Card | What you can change |
|---|---|
| Status | the status (one segment per group), and the master switch |
| Notification access | ✅ / ⚠️, why, **Open notification access** |
| One card per group | its status line; a **name** and an **emoji**; **Hours**: per day, on/off and a start and end (a day off is quiet all day; a new group starts every day 08:00–22:00, Work starts Mon–Fri 09:00–17:30); **Apps**: ✕ to remove, **➕ Add an app** (launchable apps that declare the notification permission, or **Show every app**, or type a package name), one line suggesting turning those apps' own sound off; **Pause**: **⏸️ 1 hour · 3 hours · until the next hours start** lets that group through, **Back to quiet** ends it; **🗑️ Delete the group** |
| ➕ Add a group | below the cards |

Deleting a group, or removing an app from one, offers **Undo** (the shared helper,
[ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md)), at its old place.

## The board line

One line on the always-pinned alarm board ([ADR 21](../adr/0021-alarm-board.md)), as its sub-text (and as its text
when no alarm is set); no separate notification for the status. One short segment per group, joined with " · ":

| Group state | Segment |
|---|---|
| Outside its hours | `💼 Work apps 🔕 quiet until 09:00 Mon` (no day when it is today) |
| Inside its hours | `📱 Social 🔔 on until 22:00` |
| Paused | `💼 Work apps ⏸️ let through until 14:00` |
| No day set | `💼 Work apps 🔕 quiet (no days set)` |
| Switched on, no access | `⚠️ Work apps quiet hours need notification access` (instead of the segments) |
| Off, or no groups | no line |

The board is redrawn at each group's boundaries, because the status's `refreshAt` is the earliest of them and joins the
board's own refresh time. The same tick (`DailyAlarmController.tickAll`) is what sends the change notifications.

## What it cannot do

A split second of sound can still leak before the snooze lands, which is why each group's Apps section suggests turning
those apps' own notification sound off. Notifications already in the shade when a group's hours end are left where they
are, and switching a pause on does not bring back what was snoozed earlier; they return when the hours start. The board
line cannot show app icons (Live Update text only), so each group's emoji stands in. A change notification is late when
the phone was off at the boundary.

## Where it lives

- `lib/reminders` — `WeeklyWindow` and `DayHours`: the per-day window (is it inside, next start, end of the run, next
  boundary), extracted from Exsurge, which now answers through it too.
- `lib/dailyalarms` — `QuietGroup.kt`: `QuietGroup`, `QuietKind`, `GroupState`; `WorkAppsQuiet.kt`: `WorkAppsSettings`
  (groups, and its `decide` and `status`), `PostedNotification`, `QuietDecision`, `WorkAppsStatus`, `Tally`,
  `WorkAppsCodec` (which also migrates the old single list); `AlarmBoard.workApps` carries the status.
- `app/…/workapps/` — `WorkAppsQuiet` (the hub, with `WorkAppsStore`, the remembered group states and
  `noteBoundaries`), `WorkAppsNotifier` (the change notification), `WorkAppsListenerService`, `WorkAppsLine` (the board
  text), `WorkAppsQuietScreen` and `WorkAppsGroupCard`, `WorkAppsAppPicker`, `WorkAppsQuietActivity`.

## Diagnostics

Every snooze and let-through for a chosen app is **counted** (with its group), and a line (`[workapps] … snoozing <app>
of <group> until <time> (#n for this return time; all: …)`) is logged at the first of each kind and every tenth after.
Settings changes, access changes and listener connect/disconnect each log, as does loading the settings and migrating
the old list. Every group state change is logged with whether it was announced and why not (`… went QUIET -> ON …:
telling the person`, or `not telling the person, that change is silent by rule`), and so is each notification posted.
Reports carry `workApps.enabled`, `.accessGranted`, `.groups` (name, apps, window and pause of each), `.knownKinds`,
`.status`, `.snoozed`, `.letThrough` and `.lastDecision`.

## Tests still to write (code first, 2026-11-06)

`WeeklyWindowTest` (inside, next start across a weekend, end of a run through midnight, per-day hours, a day off);
`WorkAppsSettingsTest` (`decide` for every branch, `status` for every state, the snooze return time with its margin,
no window set); `WorkAppsCodecTest` (round trip, unknown keys, `validated`); `AlarmBoardTest` (`workApps` refresh
time joins `refreshAt`); `WorkAppsQuiet` hub tests (pause, resume, `update` saves and calls `onChanged`); a device
test that the listener snoozes a posted notification. The groups and change notifications add their own list to
[todos/work-apps-quiet-hours](../todos/work-apps-quiet-hours.md) under "Tests owed".
