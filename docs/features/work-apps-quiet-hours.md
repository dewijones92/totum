---
title: Work apps quiet hours — groups of apps stay silent outside their own hours
kind: feature
area: side-quest
status: built 2026-11-06, Batch A (tests written 2026-11-07) — ships switched off; groups of apps and change notifications built the same day
updated: 2026-11-07
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
are. Starting a pause (or turning a group or the switch off, removing an app, or a group's hours ending) hands back what
this listener had snoozed for the apps that stopped being quiet: Android has no public "unsnooze", so the listener
re-snoozes each one for one second and writes how many were still snoozed 5 s later (unverified on a phone; the keys it
snoozed are kept in `work_apps_snoozed`, so a user's own snoozes are left alone). The board
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
`.status`, `.snoozed`, `.letThrough`, `.lastDecision`, `.noticesNotShown` and `.lastNoticeNotShown` (a change notice
that was not shown, for example `notifications not allowed`, is counted and logged once, never retried: the change
stays consumed). Handing back is logged as `hand back (<why>): N snoozed in all, M by this listener, K due back` and
`K asked, L still snoozed 5000ms later`.

## Change notices and edits

A settings edit announces nothing for the groups the edit itself changed (or any group when the master switch moved),
but a boundary that had been crossed for an untouched group is still announced, as the edit now runs the same boundary
check as the tick, with the edited groups silent. Before, the edit remembered every group's new state and swallowed it.

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
