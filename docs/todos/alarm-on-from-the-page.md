---
title: Set today's alarm from the alarms page, not only from the morning notification
kind: todo
status: decided — ready to build
area: side-quest
priority: medium
requested: 2026-10-12
updated: 2026-10-12
---

# Set today's alarm from the alarms page

Dewi, 2026-10-12: *"in the alarms page, I wanna be able to turn on an alarm??? not just via the
notification"*.

## How it works today (read from the code, `main` at `0c96adda`)

- The page is `DailyAlarmsScreen` (`app/…/dailyalarms/DailyAlarmsScreen.kt`). Each `AlarmCard` has a
  label, an **enabled** switch, a read-only line saying what today is doing (waiting, asking, set,
  ringing, snoozed, done), the day chips, times, time choices and ask-at. There is **no control that
  sets, changes or cancels today's alarm**.
- Today's alarm is armed only by `AlarmEvent.Answer(time)`, which the morning question sends: the
  notification's **Yes** (`DailyAlarmNotifications.answer` → `DailyAlarmReceiver` →
  `DailyAlarmController.dispatch`), or **Other time** through `QuestionActivity`.
- `DailyAlarmMachine.answer` accepts an answer only in `Asking`, `Set`, or `Done` with a skipped
  outcome (declined, cancelled, unanswered). Everywhere else it logs `answer ignored in <state>`:
  - `Idle`: before the ask time (08:00), on a day the alarm is not scheduled, and after disabling.
  - `Ringing`, `Snoozed`, `Done(RANG)`, `Done(MISSED)`.
  - A time that has already passed is refused (`answer refused: … has already gone`).
- The enabled switch does not arm anything. Turning it on runs a `Tick`: inside the asking window that
  posts the morning question; after the last ask it records `Done(UNANSWERED)`.
- No ADR argues against an in-app control. The notification is simply how ADR 18 framed the
  original request (*"an alarm clock that asks each day"*).

## Proposed shape (to confirm)

- Each card gets **Set for today** (the alarm's time choices, as the question offers them, plus
  "Other time" through the same picker). Once it is set, the card shows **Change** and **Cancel**.
- It goes through the one existing path: the page dispatches the same `Answer`, `Change` and `Cancel`
  events the notifications do, tagged `source=page` in the log, so a report can tell which one did it.
- The machine change: `answer` also accepts `Idle` for today, so you can set it before 08:00. That
  also hides the question that would otherwise come later. Refusals (ringing, a time already past)
  show their reason on the card, not in silence.
- Tests first, in `lib/dailyalarms` (`DailyAlarmMachine` is pure): answering from `Idle` before the
  ask, on a non-scheduled day, after `Done(RANG)`, and with a past time.

## Decided (2026-10-12)

Dewi: *"i wanna be able to press a button that sets e.g. the 5:30 alarm to go off later"*. He left the
rest to Claude's judgement:

1. **Set today's alarm for a time**: a button on each card, as in the proposed shape above. It is not
   about the enabled switch.
2. **Any day**, including one the alarm is not scheduled for, so a one-off Saturday works.
3. **Setting it before 08:00 cancels that day's morning question.**
4. **Today only.** Tomorrow's is set tomorrow, by the page or by the question.
5. **Yes, again after it has rung**: a second ring on the same day is set the same way. The machine
   accepts `Answer` from `Done(RANG)` and `Done(MISSED)` too, as a fresh `Set`.
