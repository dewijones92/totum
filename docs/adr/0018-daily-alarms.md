---
title: Daily alarms — asked each morning, rung by Totum itself
kind: adr
status: accepted
updated: 2026-10-08
---

# 18. Daily alarms — asked each morning, rung by Totum itself

- Status: Accepted (the per-alarm "Alarm set" notification is replaced by one board: [ADR 21](0021-alarm-board.md))
- Date: 2026-10-07

## Context

Dewi, 2026-10-07: an alarm clock that asks each day whether he wants a half-five alarm to pick up his
toddler. Three rounds of questions and a plan page (signed off "all good crack on") settled the design;
the backlog item is [todos/daily-alarms](../todos/daily-alarms.md).

## Decision

- A **list** of daily alarms (`DailyAlarm`: label, days, usual time, a time per weekday, editable time
  choices, first and last ask, re-ask interval, snooze), shipping with one, "Pick up time" at 17:30,
  **switched off**.
- A pure per-day state machine in `:lib:dailyalarms` (Idle → Asking → Set → Ringing ⇄ Snoozed → Done),
  run by `DailyAlarmController`. Asks at 08:00 on weekdays, again every hour, a last ask at 16:30 that
  stays up until the alarm time; unanswered means no alarm today. Yes (the usual time, a chip, or any
  time from a clock), No, and Change / Cancel once set.
- **Totum rings it itself.** A set alarm uses `setAlarmClock` (Doze-proof, the alarm icon shows); asks use
  exact alarms. Ringing is owned by `RingService`, a `systemExempted` foreground service (allowed for apps
  holding `USE_EXACT_ALARM`), so tone, voice and vibration keep going without the screen: the phone's alarm
  tone, the spoken line "<label>. It's half five." every 9 s (text-to-speech), and a repeating vibration,
  until Dismiss; Snooze is 5 minutes, unlimited. It pauses Totum's playback and resumes it after.
- **The alarm wins over Exsurge**: while one rings, Exsurge holds its takeover and voice (logged); its
  escalating calls bring the takeover back after.
- A ring missed while the phone was off still rings if under 30 minutes late; later is recorded `MISSED`.
- Settings → Daily alarms, and a Quick Settings tile showing today's state.

## Consequences

- Everything is logged under `dewidebug dailyalarm` with the state before and after, the machine's notes
  and the next wake; reports carry `dailyAlarms.*` per alarm.
- The tests run in the normal gate (the plan said opt-in like Exsurge's; the suite is small and fast, so
  a separate workflow was not worth it).
- The first real weekday on the phone is the proof; the emulator cannot show Doze or the phone's alarm
  volume.
