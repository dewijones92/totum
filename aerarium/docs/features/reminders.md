---
title: Reminders and money nudges
status: shipped
updated: 2026-10-31
---

# Reminders and money nudges

Pure logic in `packages/shared/src/nudges.ts`; storage and Matrix in `apps/server/src/notify/nudges.ts`.

- **Key dates** you add (renewal, maturity, fixed rate ends, MOT, other) appear under ⏰ Coming up from
  `remindDays` before (default 28) until the day. Each opens with one Matrix message, never repeated.
- **Bank consents** in their last 14 days (or already ended) appear with a link to reconnect.
- **Idle cash**: a current account (counted in net worth) whose lowest balance over the last 30 days stayed at or
  above `£3,000`, judged only when there is a balance from before the window. At most one message a month.
- **ISA allowance** for the tax year (6 April to 5 April): money paid into connected ISA accounts, not interest and
  not transfers from another ISA, plus payments you type in for ISAs Aerarium cannot see. Used, left, days to
  5 April; the £12,000 cash ISA cap for under-65s appears from the 2027/28 tax year.
- Shown on `/reminders` and the first four on the overview.
- Not built: savings-rate drop detection (no rate data from providers) and best-buy comparison (phase 2 scout).

Tests: `packages/shared/test/nudges.test.ts`, `apps/server/test/nudges.test.ts`, `e2e/reminders.spec.ts`.
