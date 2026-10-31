---
title: Transactions and outgoings
status: shipped
updated: 2026-10-31
---

# Transactions and outgoings

All the logic is pure and lives in `packages/shared/src/analysis.ts`; the server runs it over the last ~800 days of
stored transactions on each request.

- **Transfers between your own accounts** (savings moves, card repayments) are paired: a debit in one account and a
  credit of the same amount and currency in another within 3 days. Both sides are tagged 🔁 and excluded from
  spending and from regular payments, so nothing is counted twice.
- **Categories** come from generic keyword rules (no provider names in this public repo). A credit is income unless a
  rule says otherwise; an outflow is never income.
- **Regular payments** group outflows by account and normalised payee (`payeeKey`), then look for a weekly, monthly,
  quarterly or yearly rhythm (median gap within tolerance, 70% of gaps fitting) with amounts within 3× of each other.
  Each gets its typical and latest amount, a monthly equivalent, the next expected date, and a ⚠️ price change when the
  latest differs from the typical by 5% or more. A payment that missed several cycles is 💤 stopped and drops out of
  the monthly total.
- **Transactions** (`/transactions`): search payee or description, filter by account or category; joint accounts are
  tagged 👥, transfers faded.
- **Outgoings** (`/outgoings`): the monthly total of live regular payments (and how much comes from joint accounts),
  what is due in the next two weeks, and every regular payment found, stopped ones last.

Tests: `packages/shared/test/analysis.test.ts` (a synthetic year: monthly bill with a 10% rise, yearly subscription,
stopped gym, irregular cafe, salary, savings transfer, card repayment, interest), `apps/server/test/app.test.ts`,
`e2e/money.spec.ts`.
