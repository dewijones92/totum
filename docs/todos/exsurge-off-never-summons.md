---
title: Exsurge off never summons; one nudge a day
kind: todo
status: done 2026-10-22 (ADR 71)
area: side-quest
priority: high
requested: 2026-10-22
updated: 2026-10-22
---

# Exsurge off never summons; one nudge a day

Off means no summons ever, active hours or not. When active hours start and it is off, one silent notification a day: "🏛️ Exsurge is off — switch it on?" with an On button (Dewi's choice). The flat-phone "Sat down?" nudge stays. Code says `ExsurgeMachine.arrive()` already checks `enabled`; check on a phone why Dewi saw a summons while off.

Done 2026-10-22: the cause was ADR 38's "off for today" default, which switched Exsurge back on at the next active
day. Off now stays off; the daily reminder is `ExsurgeOffReminder` (ADR 71).
