---
title: A reminder is a daily alarm that nags, and every time is one tap away
kind: adr
status: accepted
updated: 2026-10-29
---

# 92. A reminder is a daily alarm that nags, and every time is one tap away

- Status: Accepted
- Date: 2026-10-29

## Context

Dewi, 2026-10-29: *"sometimes in the night, I forget to close my fridge … I've had to throw the food the next day a
couple of times … every night it reminds me to close my fridge at 9 PM … visible in the same place that alarms are …
configurable … Also … the daily alarm edit and add UI … I just want to be able to easily change the time or add the
time."* A scout counted the taps: changing an alarm's time took five, behind Edit; "Add a daily alarm" made an alarm
switched off at 17:30 without opening anything; a one-off could only be cancelled.

His answers: the reminder **nags until Done** (a heads-up with a sound at its time, again every 10 minutes, about an
hour, no morning question); **general reminders**, with the fridge as the first; **tap the time** on a card to change
it, with −15/+15, one ➕ sheet for every kind of new alarm, new ones switched on; delete with Undo; one-offs editable;
a new usual time moves every day.

## Decision

- **No third system.** A reminder is a `DailyAlarm` with `kind = REMINDER` (`AlarmKind`, default `ALARM`, so stored
  alarms read as before). The same store, machine, board, page, sheet, diagnostics and stats.
- **In the machine** a reminder's day starts at midnight (not `askAt`) already `Set` for its time, so it never asks.
  At its time it goes to a new state, `Reminding(date, time, nudges, next)`, and emits `Remind(nudge)`, never
  `Ring`; it nudges every `snoozeMinutes` (10 by default) up to `REMINDER_NUDGES` (6), then stays put quietly with the
  notification up. Done (`Dismiss`) ends it as `Done(RANG)`; Snooze hides it until the next nudge; a reminder still
  nagging the next day is put away (`StopReminding`). Started after its time it still nags within the 30 minute grace,
  later it is `MISSED`. Switching an alarm to a reminder mid-question puts the question away; the other way stops the
  nag.
- **Android**: a "Reminders" channel at high importance with sound, so it pops up; the nag carries ✅ Done and Snooze.
  A reminder is scheduled as an exact alarm, not an alarm clock (no clock icon in the status bar, no system "next
  alarm"), and the board never heralds it. On the board it is a row like any alarm ("📌 reminder", "🔔 now, until
  done"), its action while nagging is "✅ Close the fridge: done", and while set "Skip 21:00" instead of Change.
- **The fridge** (`DailyAlarm.FRIDGE`: "Close the fridge", 21:00, every day, on) is added once per install by
  `DailyAlarmController`'s `seeds`; the ids already seeded are stored (`seeded`), so deleting it does not bring it back.
- **Editing.** Tapping a card's big time opens the clock straight away; −15/+15 sit beside it; the day initials toggle
  in place; everything rarer is under ⚙️ More (title, Alarm/Reminder, each day's time, the question's choices and ask
  times, or the nag interval), with 🗑️ Delete and an Undo snackbar. `DailyAlarm.retimed` moves every day's own time
  and the choices by the same amount as the usual time; `DailyAlarmController.retime` also moves today's set time, or
  cancels today when the new time has already gone (so the old time cannot still ring). A one-off's time and title
  are changed by tapping them (`SetOn` on its date; a time already gone is refused).
- **The ➕ sheet** is the one way to add: title, ⏰ Alarm / 🔔 Reminder, Once / Every day / Weekdays / Choose days;
  Once keeps the +15 min … chips and the day; repeating takes a time. New repeating ones start on
  (`addRepeating`). The clock dialog (`TimePickDialog`, shared) can switch between the dial and typing.

## Consequences

- An alarm and a reminder cannot drift apart: a fix to the board, the store or the page serves both.
- `Reminding` is counted as `RINGING` in the stats log, so a nagged reminder counts as rung.
- The board notification can now carry a reminder's Done action, so a nagging fridge can be cleared from the lock
  screen without opening the app.
- Tests: `ReminderTest` and `RetimeTest` (`:lib:dailyalarms`), eight controller tests, `AlarmPageEditTest` on the
  device.
