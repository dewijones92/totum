---
title: Daily alarms are shown on one pinned board, soonest first
kind: adr
status: accepted
updated: 2026-10-08
---

# 21. Daily alarms are shown on one pinned board, soonest first

- Status: Accepted
- Date: 2026-10-08
- Amends: [ADR 18](0018-daily-alarms.md) (the per-alarm "Alarm set" notification is replaced)

## Context

Dewi, 2026-10-08: *"I want the alarms to be grouped by one notification in a list ordered by what is coming
soonest … very notification sticky … just like the other Totum stuff."* Each alarm had its own swipeable
"Alarm set for 17:30" notification. He chose: today and upcoming days, **one row per alarm** (its next
occurrence); pinned **like the Exsurge banner**; the morning question **stays its own pop-up**; shown **only
while something is set or being asked today**; a skipped day shows as **skipped**; buttons **Change / Cancel**
for the soonest set alarm, **Set it after all** for a skipped one, and **Open**.

> Amended by [ADR 23](0023-pinned-notifications-alert-silently.md) (2026-10-08): the board is now always pinned,
> with a heading for each case, on an alerting-but-silent channel.

## Decision

- `alarmBoard()` in `:lib:dailyalarms` (pure): one row per enabled alarm — today's state when it is set,
  asking, snoozed, ringing or skipped (until its time passes), otherwise its next day and ask time — sorted by
  when it happens. `pinned` while anything today is set, asking, snoozed or ringing.
- The controller publishes the board on every change (`DailyAlarmPorts.showBoard`); the machine's
  ShowSet/HideSet effects no longer post anything of their own.
- `AlarmBoardService`, a systemExempted foreground service (as the ring service), holds the board
  notification; it starts when the board is pinned and stops when it is not. The notification is big text
  (Android 16 promotes only plain or big-text layouts) and asks to be a Live Update with a countdown to the next
  alarm, through the reminder kit's `LiveUpdate`, which the Exsurge banner now shares.
- Android 14+ lets anyone swipe a foreground notification, so, as with the Exsurge banner (ADR 5), its delete
  intent puts it straight back (`DailyAlarmReceiver.REPOST`).
- **Set it after all:** a day declined, unanswered or cancelled accepts an answer again; a day that rang does
  not.

## Consequences

- One notification for all alarms, pinned only on days something is set or being asked.
- On a day with Exsurge on and an alarm set, both banners show.
- Logged as `dewidebug dailyalarm board pinned=… next=… rows=…` and the service's start, stop and
  promotion; `dailyAlarms.board.*` in every report.
- Verified on `totum-api35`: switching the alarm on at 10:38 pinned the board (*17:30 Pick up time · today ·
  asking*); answering Yes made it *Next: Pick up time 17:30 · in 6h* with Change / Cancel / Open; Clear all
  left it; Cancel unpinned it and stopped the service.
