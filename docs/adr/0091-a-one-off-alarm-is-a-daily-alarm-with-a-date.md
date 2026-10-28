---
title: A one-off alarm is a daily alarm with a date
kind: adr
status: accepted
updated: 2026-10-28
---

# 91. A one-off alarm is a daily alarm with a date

- Status: Accepted
- Date: 2026-10-28

## Context

Dewi, 2026-10-28: *"I want a way to easily set alarms … seven PM tonight, titled deployed to production … easy to
create in our app, we have an alarm screen."* No voice entry. His answers: a ➕ button on the alarms page opening a
quick sheet; once rung the alarm leaves the list but its title stays as a recent; reachable from a home-screen
shortcut, a Quick Settings tile and a Library tile; the same full-screen ring as the daily alarms, the voice saying the
title.

## Decision

- **No second alarm system.** A one-off alarm is a `DailyAlarm` with `once` set (`DailyAlarm.oneOff`, id prefix
  `once-`): the same store, state machine (`DailyAlarmMachine`), board notification, ring service, ring screen, snooze,
  voice (`spokenLine` says its title), stats and diagnostics. The machine treats it as never asking a morning
  question (`Idle` stays `Idle`, no next wake), ignores Change (it is edited from its page), keeps a `Set` for a later
  date across midnight, and a new event `SetOn(date, time)` sets it. The board lists a `Set` on any later date, not
  only today.
- `DailyAlarmController.addOneOff` refuses a time already gone; once a one-off ends (rang, cancelled or never set) the
  controller removes it; a missed one (the phone off or asleep past its 30 min grace) stays on the page as "⚠️ Missed"
  until the next day or until cancelled, so it does not vanish unseen. Removing one unschedules it; every one-off's
  title is kept in `recentTitles` (newest first, eight, no repeats ignoring case), which the sheet offers as chips.
- **The sheet** (`NewAlarmSheet`): title, recent titles, +15 min · +30 min · +1 h · +2 h (counted from the start of the
  current minute), a clock, Today · Tomorrow · a date; a time with no day chosen is today while it is still to come,
  else tomorrow (`OneOffWhen`). Set says "✅ Alarm set for 19:00 today".
- **Ways in**: the alarms page (now titled Alarms, one-off alarms above the daily ones), a static app-icon shortcut
  (`res/xml/shortcuts.xml`, action `com.dewijones92.totum.NEW_ALARM`), a "New alarm" Quick Settings tile
  (`NewAlarmTileService`) and an Alarms tile on the Library hub showing the next alarm. The board notification
  offers Cancel, not Change, for a one-off.

## Consequences

- The one-off and the daily alarms cannot drift apart: a ringing fix serves both.
- The static shortcut counts towards the launcher's limit beside the dynamic Recently shortcuts.
- Tests: `OneOffAlarmTest` and `OneOffWhenTest` (`:lib:dailyalarms`), four controller tests, `NewAlarmSheetTest`
  on the device.
