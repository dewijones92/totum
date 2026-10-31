---
title: Health dashboard
status: in progress
updated: 2026-10-31
---

# Health dashboard

See [ADR 0006](../adr/0006-a-health-dashboard-that-shouts.md).

- A tile per connected source (green, amber or red) with its reasons, last good sync, consent expiry,
  **Sync now** and its recent runs; a tile per typed-in balance (amber when older than `MANUAL_STALE_DAYS`,
  default 90); a tile for the app (scheduler heartbeat, version, database size).
- A bar under the header on **every page** while anything needs attention: red and `role="alert"` when
  something is broken (only the broken items listed), amber otherwise. It refreshes every 30 seconds.
- Health is computed from the sync-run log on each request (`judgeConnection`, `judgeManual`,
  `judgeScheduler` in `packages/shared/src/health.ts`), never stored as a flag.
- `/healthz` reports the scheduler's last tick for the deploy check.
- Still to come: a Matrix message when a source changes state.

Tests: `packages/shared/test/health.test.ts`, `apps/server/test/app.test.ts`, `apps/web/test/app.test.tsx`,
`e2e/health.spec.ts`.
