---
title: A quick alarm from the alarm notification, with saved presets
kind: adr
status: accepted
updated: 2026-10-30
---

# 99. A quick alarm from the alarm notification, with saved presets

- Status: Accepted
- Date: 2026-10-30

## Context

Dewi, 2026-10-30: *"I have a meeting in like 30 minutes … 3 minutes before the meeting, I want an alarm to set off so
that … I run up and downstairs 5 times … at the moment I have to set an alarm name, etc. … I click … the alarm banner
… and then I could bam, really, really quickly."* The ➕ sheet ([ADR 91](0091-a-one-off-alarm-is-a-daily-alarm-with-a-date.md))
was reachable from the board only by opening the alarms page, then ➕, a title, a time.

His answers: a ⚡ button on the alarm notification; the alarm's own time; saved presets, starting on the last one
used; a full alarm; the clock dial on screen straight away; presets managed on that screen.

## Decision

- **⚡ Quick alarm is an action on the alarm board notification**, in every state (the board uses at most two others),
  opening `QuickAlarmActivity`, which shows over the lock screen like the question screen.
- **The screen**: preset chips (the last one used first and chosen), the shared clock dial (`TimeDial`, now also the
  body of `TimePickDialog`) starting 15 minutes ahead, "⏰ Rings 09:26 today, in 27 min", and ✅ Set. Long-press a
  preset to rename or delete it; ➕ Preset adds one and chooses it.
- **Set is a one-off** (`DailyAlarmController.setQuickAlarm`): `addOneOff` at the next occurrence of the time
  (tomorrow if it has gone, `OneOffWhen.at`), kind ALARM, so it rings full screen like every alarm. The preset used
  moves first.
- **Presets** are titles in the alarms' store (`quickPresets`), pure list operations in `:lib:dailyalarms`
  (`QuickPresets`), seeded once with "🏃 Run up and down the stairs ×5" (the seed is recorded with the fridge's, so
  deleting it lasts).

## Consequences

- Logged: `quick alarm screen opened (N preset(s))`, `quick alarm "<title>" for <date time> from quick screen: set=…`,
  `quick presets <change> -> [...]`; reports carry `dailyAlarms.quickPresets`.
- Verified on totum-api35: ⚡ on the board beside Skip and Open, the screen with the stairs chosen, Set made a one-off
  and an alarm clock at the time shown.
- Tests: `QuickPresetsTest` (`:lib:dailyalarms`), four controller tests, `QuickAlarmScreenTest` on the device.
