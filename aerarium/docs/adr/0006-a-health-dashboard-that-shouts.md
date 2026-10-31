---
title: A health dashboard that shouts
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0006 · A health dashboard that shouts

## Context

Dewi: "if an API is down or something, then I want it to be really visually known."

## Decision

- Every sync run is recorded (source, start, end, outcome, counts, error text). Health is derived from that log
  and from consent/token expiry and manual-balance age, never kept as a separate flag that could drift.
- Each source is green, amber or red. While anything is red, a red bar sits on every page, naming the source and
  the fix.
- The app watches itself too: the scheduler's heartbeat, the database, the deployed version. `/healthz` reports
  the scheduler's last tick, so a dead scheduler fails the deploy health check instead of going quiet.
- Matrix gets a message when a source changes state (green to red, red to green), never once per failed run.

## Consequences

A broken source can't hide behind stale numbers, and the alerts never turn into noise.
