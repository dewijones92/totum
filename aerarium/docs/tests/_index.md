---
title: Aerarium tests
kind: index
updated: 2026-10-31
---

# Tests

Pyramid: many vitest unit tests (pure analysis, connectors against fake providers, API routes via
`app.request`), a few Playwright e2e flows against the built server.

| Layer | Where                  | What                                                   |
| ----- | ---------------------- | ------------------------------------------------------ |
| Unit  | `packages/shared/test` | schemas and shared pure logic                          |
| Unit  | `apps/server/test`     | guards, routes, connectors, analysis                   |
| Unit  | `apps/web/test`        | components (jsdom)                                     |
| Unit  | `tools/test`           | the privacy guard's patterns                           |
| E2E   | `e2e/shell.spec.ts`    | the built server answers `/healthz` and serves the app |
