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

| Source                        | Status                                 | How                                                                                                                                                                                                                                                                                                                                                                            |
| ----------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Trading 212                   | shipped                                | API key ID + secret (read-only permissions) pasted once, stored encrypted ([ADR 0007](../adr/0007-secrets-encrypted-key-outside-backups.md)). Reads the account value (`/equity/account/summary`) and every page of deposits and withdrawals (`/history/transactions`), every 6 hours. Verified against the live API on 2026-10-31 (one account, balance and deposits parsed). |
| Enable Banking (Open Banking) | built, waiting for the application key | Pick a bank from the UK list, approve in the bank's app, come back to Connections; renew with 🔑 Reconnect on the health tile. See [ADR 0010](../adr/0010-open-banking-through-enable-banking.md). Off until `EB_APPLICATION_ID`, `EB_PRIVATE_KEY_FILE` and `EB_REDIRECT_URL` are set.                                                                                         |
| Monzo                         | planned                                | step 5                                                                                                                                                                                                                                                                                                                                                                         |

Tests: `apps/server/test/enable-banking.test.ts` (JWT, balance choice, paging, booked only, stable ids, consent round trip, renewal, refused states, no payment endpoints), `apps/server/test/trading212.test.ts` (paging, auth, 429 with Retry-After, refused calls, secrets never returned).
