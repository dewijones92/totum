---
title: Aerarium tests
kind: index
updated: 2026-10-31
---

# Tests

The e2e server runs with `FAKE_PROVIDERS=1`, which adds a synthetic connector and `/api/dev/fake/*` controls; production never sets it.

Pyramid: many vitest unit tests (pure analysis, connectors against fake providers, API routes via
`app.request`), a few Playwright e2e flows against the built server.

| Layer | Where                  | What                                                                                                                   |
| ----- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Unit  | `packages/shared/test` | schemas and shared pure logic                                                                                          |
| Unit  | `apps/server/test`     | guards, routes, connectors, analysis                                                                                   |
| Unit  | `apps/web/test`        | components (jsdom)                                                                                                     |
| Unit  | `tools/test`           | the privacy guard's patterns                                                                                           |
| E2E   | `e2e/overview.spec.ts` | `/healthz`; a typed-in balance reaches net worth and an update moves it                                                |
| E2E   | `e2e/health.spec.ts`   | a fake source that fails shows its error on the health page, recovers on Sync now, and its accounts reach the overview |
| E2E   | `e2e/whose.spec.ts`    | a newly synced account is flagged on the overview, asks "Mine or Joint?", and Joint becomes a tag                      |
| E2E   | `e2e/money.spec.ts`    | a monthly bill appears on Outgoings with its 10% rise; the feed searches and tags a card repayment as a transfer       |
| E2E   | `e2e/reports.spec.ts`  | last week's report made on demand shows where the money went and the allowance line                                    |
