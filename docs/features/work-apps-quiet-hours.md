---
title: Work apps quiet hours — Slack, Outlook and Teams stay silent outside work
kind: feature
area: side-quest
status: built 2026-11-06 — ships switched off; code first, tests and an on-phone check still to come
updated: 2026-11-06
---

# 🔕 Work apps quiet hours

**Ask (Dewi, 2026-11-05/06):** silence Slack, Outlook and Teams outside 09:00–17:30 on weekdays and all day at
weekends, "sensibly configurable", shown as one line on the always-pinned alarms board. Decision:
[ADR 124](../adr/0124-work-apps-are-snoozed-outside-work-hours.md); backlog:
[todos/work-apps-quiet-hours](../todos/work-apps-quiet-hours.md).

## What it does

- A `NotificationListenerService` (`WorkAppsListenerService`) sees every notification the chosen apps post. **Outside
  the work window** it snoozes each one (`snoozeNotification`) until the window starts, so Monday's 09:00 brings back
  what arrived over the weekend. Inside the window, or when paused, or for an app that is not chosen, nothing happens.
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
| Status | the one-line status, and the master switch |
| Notification access | ✅ / ⚠️, why, **Open notification access** |
| Work hours | per day: work day on/off and a start and end (a day off is quiet all day); default Mon–Fri 09:00–17:30 |
| Apps | the chosen apps (default Slack `com.Slack`, Outlook `com.microsoft.office.outlook`, Teams `com.microsoft.teams`), ✕ to remove, **➕ Add an app** (launchable apps that declare the notification permission, or **Show every app**, or type a package name); one line suggesting turning those apps' own sound off |
| Pause | **⏸️ 1 hour · 3 hours · until the next work day** lets everything through; **Back to quiet** ends it |

## The board line

One line on the always-pinned alarm board ([ADR 21](../adr/0021-alarm-board.md)), as its sub-text (and as its text
when no alarm is set); no separate notification:

| State | Line |
|---|---|
| Outside work | `🔕 Slack · Outlook · Teams quiet until 09:00 Mon` (no day when it is today) |
| Inside work | `💼 Work apps on until 17:30` |
| Paused | `⏸️ Work apps let through until 14:00` |
| Switched on, no access | `⚠️ Work apps quiet hours need notification access` |
| Off | no line |

The board is redrawn at each boundary, because the status's `refreshAt` joins the board's own refresh time.

## What it cannot do

A split second of sound can still leak before the snooze lands, which is why the Apps card suggests turning those
apps' own notification sound off. Notifications already in the shade when work ends are left where they are, and
switching a pause on does not bring back what was snoozed earlier; they return at the work start.

## Where it lives

- `lib/reminders` — `WeeklyWindow` and `DayHours`: the per-day window (is it inside, next start, end of the run, next
  boundary), extracted from Exsurge, which now answers through it too.
- `lib/dailyalarms` — `WorkAppsQuiet.kt`: `WorkAppsSettings` (and its `decide` and `status`), `PostedNotification`,
  `QuietDecision`, `WorkAppsStatus`, `Tally`, `WorkAppsCodec`; `AlarmBoard.workApps` carries the status.
- `app/…/workapps/` — `WorkAppsQuiet` (the hub, with `WorkAppsStore`), `WorkAppsListenerService`, `WorkAppsLine`
  (the board text), `WorkAppsQuietScreen`, `WorkAppsAppPicker`, `WorkAppsQuietActivity`.

## Diagnostics

Every snooze and let-through for a chosen app is **counted**, and a line (`[workapps] … snoozing <app> until <time>
(#n for this return time; all: …)`) is logged at the first of each kind and every tenth after. Settings changes,
access changes and listener connect/disconnect each log. Reports carry `workApps.enabled`, `.accessGranted`, `.apps`,
`.window`, `.pausedUntil`, `.status`, `.snoozed`, `.letThrough` and `.lastDecision`.

## Tests still to write (code first, 2026-11-06)

`WeeklyWindowTest` (inside, next start across a weekend, end of a run through midnight, per-day hours, a day off);
`WorkAppsSettingsTest` (`decide` for every branch, `status` for every state, the snooze return time with its margin,
no window set); `WorkAppsCodecTest` (round trip, unknown keys, `validated`); `AlarmBoardTest` (`workApps` refresh
time joins `refreshAt`); `WorkAppsQuiet` hub tests (pause, resume, `update` saves and calls `onChanged`); a device
test that the listener snoozes a posted notification.
