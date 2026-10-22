---
title: Exsurge switched off comes back on when the next active day starts
kind: adr
status: superseded
updated: 2026-10-22
---

# 38. Exsurge switched off comes back on when the next active day starts

- Status: Superseded by [ADR 71](0071-exsurge-off-stays-off-and-reminds-once-a-day.md) (2026-10-22: off now stays off)
- Date: 2026-10-14

## Context

Dewi, 2026-10-14, asked what happens when he forgets to turn Exsurge back on (it stayed off for good) and
chose "Off = off for today".

## Decision

- A setting, **Off means off for today**, on by default. Switching Exsurge off records
  `backOnAtEpochMs`: the start of the next active day after today (`ExsurgeSettings.backOnAfterOff`), so a
  Friday turn-off comes back on Monday and an early-morning one waits for tomorrow.
- That time joins the controller's wake alarm; the first event at or after it switches Exsurge on
  (`[Exsurge] back on after a day off`). Turning it on by hand, or the setting off, clears it.
- Reports carry `exsurge.backOnAt`.

## Consequences

- Forgetting costs at most the rest of a day. To keep it off for longer, switch the setting off.
