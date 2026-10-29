---
title: Nightly reminders (close the fridge) and a one-tap alarm page
kind: todo
area: side-quest
priority: high
status: built 2026-10-29 (ADR 92), awaiting Dewi on the phone
updated: 2026-10-29
---

# Reminders and a one-tap alarm page

**Ask (Dewi, 2026-10-29):** *"sometimes in the night, I forget to close my fridge … every night it reminds me to close
my fridge at 9 PM … visible in the same place that alarms are … configurable … Also … the daily alarm edit and add UI
… I just want to be able to easily change the time or add the time."*

**His answers (AskUserQuestion, 2026-10-29):**
- Insistence: **nag until I tap Done** (heads-up with a sound, again every 10 min, about an hour; no morning question).
- Scope: **general reminders**, the fridge the first one (21:00 every day, on).
- Layout: **tap the time** on a card (clock straight away, −15/+15), one ➕ sheet for every new alarm
  (Once / Every day / Weekdays / Custom, Alarm / Reminder), new ones on, rarer settings under More.
- Also: delete with Undo; one-offs editable; a new usual time moves every day.

**Done:** [ADR 92](../adr/0092-a-reminder-is-a-daily-alarm-that-nags-and-every-time-is-one-tap.md),
[features/daily-alarms](../features/daily-alarms.md#reminders-and-the-one-tap-page-2026-10-29-adr-92).

**Still to see on the phone:** the nag's sound and heads-up on Dewi's Pixel (the emulator is muted), and whether ten
minutes is the right gap.
