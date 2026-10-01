---
title: Exsurge et Disce is a side-quest module
kind: adr
status: accepted
updated: 2026-10-01
---

# 2. Exsurge et Disce is a side-quest module: a pure brain plus Android adapters

- Status: Accepted
- Date: 2026-10-01

## Context

Dewi wanted a feature that, after he has sat for 30 minutes, makes him stand for five minutes and
use Loquax (his language app). It is *"really a side quest to the main app"*: it has nothing to do
with video or podcasts, so the Unified law has no pillars to split. What it does have is a lot of
time-driven behaviour (sitting clock, escalation, snoozes, active hours, a break) that several
Android entry points must agree on: an alarm, a foreground service, a full-screen activity,
notification actions and a Quick Settings tile.

## Decision

- `:lib:exsurge` is **pure JVM**, like `:lib:ytdlp`. It holds one sealed state machine
  (`ExsurgeMachine`: state + event → state + effects + notes), the settings and active hours, the
  step window, and the stats (laurels, ranks, streak).
- Deadlines are **derived from the settings**, not stored, so a changed setting applies at once.
  A late alarm is caught up by replaying ticks, not trusted to be on time.
- The Android side (`app/…/exsurge/`) only feeds events in and carries effects out, through
  `ExsurgeController` and the `ExsurgePorts` interface. No Android piece decides eligibility,
  remaining time or stats for itself.
- State lives in its own SharedPreferences file and an outcomes JSON file, **not in Room**, so the
  side quest can never put the main database's schema at risk.

## Consequences

- The behaviour is exhaustively testable in milliseconds (56 JVM tests, 96% line coverage), and
  `ExsurgeController` is tested on the JVM with recording ports.
- Removing the module means deleting one library, one package and a handful of manifest entries.
