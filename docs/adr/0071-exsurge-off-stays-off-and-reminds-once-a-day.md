---
title: Exsurge switched off stays off, and reminds once a day
kind: adr
status: accepted
updated: 2026-10-22
---

# 71. Exsurge switched off stays off, and reminds once a day

- Status: Accepted; supersedes [ADR 38](0038-exsurge-off-means-off-for-today.md)
- Date: 2026-10-22

## Context

ADR 38 made "off" mean "off for today": turning Exsurge off scheduled `backOnAtEpochMs` for the next active day, and
it switched itself back on and summoned again. Dewi, 2026-10-22, was summoned while he believed it was off, and chose:
off never summons; one nudge a day.

## Decision

- Off stays off until it is turned on. Nothing switches it back on by itself; the "Off means off for today" setting
  is gone (its stored value is ignored).
- While off, the controller wakes at the next active-hours start (`ExsurgeSettings.nextActiveStart`, so turning it
  off before today's hours begin still reminds today) and posts one silent notification, "🏛️ Exsurge is off", with
  **Turn on** (`ExsurgeOffReminder`, its own channel). It then sets the next reminder for the following active start.
  `remindAtEpochMs` holds when; reports carry `exsurge.remindAt`.
- The flat-phone "Sat down?" nudge (ADR 37) stays as it is.

## Consequences

- A summons can only follow Dewi turning Exsurge on. `ExsurgeControllerTest` holds it: off, the alarm at the next
  active start reminds once, leaves it off and sets tomorrow's reminder.
- A phone that was off at the reminder time gets it at the next wake after (the alarm is exact; the dispatch catches up).
