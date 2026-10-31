---
title: Overview and typed-in accounts
status: shipped
updated: 2026-10-31
---

# Overview and typed-in accounts

- **Overview** (`/`): household net worth (assets minus everything owed), "yours" (half of joint accounts),
  assets and owed, a daily net-worth chart, and a card per account grouped by type. Debts show as negative.
  Accounts excluded from net worth, without a balance yet, or in another currency are counted and said so.
- **Accounts** (`/accounts`): add a balance you type in (savings, pensions, the mortgage, anything without a
  connection), update it with an "as of" date (history is kept), set mine/joint, include or exclude it, remove it.
  Synced accounts can be set mine/joint and excluded, but not edited by hand.
- Net worth is snapshotted once a day by the scheduler tick (`net_worth_days`).

Files: `packages/shared/src/net-worth.ts`, `apps/server/src/store/accounts.ts`, `apps/server/src/app.ts`,
`apps/web/src/dashboard/`, `apps/web/src/accounts/`. Tests: `packages/shared/test/money.test.ts`,
`apps/server/test/app.test.ts`, `e2e/overview.spec.ts`.
