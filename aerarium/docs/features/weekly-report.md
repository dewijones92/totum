---
title: Weekly money report
status: shipped
updated: 2026-10-31
---

# Weekly money report

See [ADR 0008](../adr/0008-a-weekly-money-report.md). Pure computation in `packages/shared/src/report.ts`;
the job in `apps/server/src/notify/weekly.ts`.

- **When**: the first scheduler tick at or after 07:00 UTC on a Monday makes the report for the previous
  Monday–Sunday, stores it (`weekly_reports`) and sends a one-line Matrix headline with a link. Once per week.
  📊 **Make last week's report now** on `/reports` makes it on demand and sends nothing (you are already looking).
- **Where the change came from**: saved (income minus spending, transfers between your own accounts excluded),
  markets (each investment, ISA or pension account's value change minus what went into it that week), interest,
  debt paid down, and what it **could not explain** (typed-in balance updates, timing), shown rather than hidden.
- **Spending vs usual**: the week against the average week of the past year, and the three biggest categories
  against their usual.
- **The year**: change since 1 January, a 12-month projection at the rate so far, and the week's savings rate.
- **Tax**: interest earned outside ISAs since 6 April against the Personal Savings Allowance (`£1,000` by default,
  the basic-rate figure).
- Still to come: the Sonnet commentary (phase 2) and the tax-year review after 5 April.

Tests: `packages/shared/test/report.test.ts` (hand-worked week), `apps/server/test/weekly.test.ts`,
`e2e/reports.spec.ts`.
