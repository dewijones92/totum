---
title: Connections
status: in progress
updated: 2026-10-31
---

# Connections

`/connections` lists what is connected (disconnect removes its accounts and history) and adds new sources.
Every connector reaches its provider only through `ReadOnlyHttp` (`apps/server/src/http/read-only.ts`), built
with the exact methods, host and paths it may call; anything else throws `ReadOnlyViolation` before a request
leaves the process ([ADR 0002](../adr/0002-read-only-everywhere.md)). Every call is logged with method, path,
status, size and time, never its body.

| Source                        | Status  | How                                                                                                                                                                                                                                                                                                                                                                            |
| ----------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Trading 212                   | shipped | API key ID + secret (read-only permissions) pasted once, stored encrypted ([ADR 0007](../adr/0007-secrets-encrypted-key-outside-backups.md)). Reads the account value (`/equity/account/summary`) and every page of deposits and withdrawals (`/history/transactions`), every 6 hours. Verified against the live API on 2026-10-31 (one account, balance and deposits parsed). |
| Enable Banking (Open Banking) | planned | step 4                                                                                                                                                                                                                                                                                                                                                                         |
| Monzo                         | planned | step 5                                                                                                                                                                                                                                                                                                                                                                         |

Tests: `apps/server/test/trading212.test.ts` (paging, auth, 429 with Retry-After, refused calls, secrets never returned).
