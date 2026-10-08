---
title: The Exsurge banner and the alarm board alert silently, so they show on every lock screen, and the board is always pinned
kind: adr
status: accepted
updated: 2026-10-08
---

# 23. The Exsurge banner and the alarm board alert silently, so they show on every lock screen, and the board is always pinned

- Status: Accepted (amends [ADR 15](0015-exsurge-banner-is-a-live-update.md) and [ADR 21](0021-alarm-board.md))
- Date: 2026-10-08

## Context

Dewi, 2026-10-08: *"make sure the foreground notifications, lock screen notifications are always showing in
Totum for the Exsurge (and alarms), no matter what state it's in"*. His phone runs Android 16 **36.0**; Live
Updates (ADR 15) need 36.1, and report 0.1.587 showed the banner never promoted
(`requested=false promotable=false sdk=36.0`). Both notifications sat on `IMPORTANCE_LOW` channels, which
Android treats as silent, and a Pixel hides silent notifications from the lock screen by default. He chose,
from options put to him: show them, still quiet (rather than wait for 36.1 or change a phone setting); and
pin the alarm board always (rather than only while something is set or asked today).

## Decision

- `reminders/kit/PinnedChannel` makes one kind of channel for both: `IMPORTANCE_DEFAULT` (alerting, so shown
  on the lock screen and always-on display), no sound, no vibration, no badge, public on the lock screen. The
  notifications keep `setOnlyAlertOnce` and `VISIBILITY_PUBLIC`.
- An app cannot raise an existing channel's importance, so both move to new ids, `exsurge_pinned` and
  `dailyalarm_board`; the old `exsurge_banner` and `dailyalarm_set` are deleted when the new ones are made.
- The alarm board is always pinned. `AlarmBoard.heading` replaces `pinned`: Next (an alarm set, snoozed or
  ringing), AskingToday, LaterToday, NothingToday ("No alarm today"), AlarmsOff ("Alarms off", with a
  **Turn on** button that switches every alarm on) and NoAlarms ("No alarms").
- Because it is always visible, the board is redrawn at the next local midnight (`AlarmBoard.refreshAt`,
  scheduled through the ports), so "tomorrow" never stays "tomorrow".

## Consequences

- Both show on the lock screen on Android 16 36.0 and earlier; on 36.1 they are Live Updates as before.
- They sit in the shade's alert section rather than the silent one, and their icons show in the status bar.
  No sound or buzz.
- A user who had switched the old channels off finds the new ones on.
- `exsurge.bannerChannel` in diagnostics and the board's `dewidebug dailyalarm board pinned=…` line describe
  each channel (`importance=alerting lockScreen=… sound=false vibrates=false appNotificationsOn=…`); the
  board line also carries `heading=` and `refreshAt=`.
- Verified on `totum-api35` with a swipe lock: "Alarms off" and the Exsurge banner shown on the lock screen
  (grouped by Android as one Totum card).
