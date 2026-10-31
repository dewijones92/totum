---
title: Alerts
status: in progress
updated: 2026-10-31
---

# Alerts

Messages go to the Pi's Matrix alerts room (the same room and bot credentials as dot-files' `matrix/notify.sh`,
mounted read-only), or only to the log when `MATRIX_*` is not set.

- **A source breaks or recovers**: after every scheduler tick the health report is compared with the last one
  (kept in settings). A change to or from red sends one message ("🔴 … is broken (reason) <link>",
  "🟢 … is working again"). Amber wobbles and repeated failures send nothing more.
- **A large transaction**: after each successful sync, any newly stored transaction of at least
  `LARGE_TRANSACTION_MINOR` (default £500) booked in the last 7 days, that is not a transfer between your own
  accounts, sends "💷 £X into/out of <account>: <payee> (<date>)". Backfill from a first sync is older than 7 days
  and stays quiet; a transaction is only ever new once.
- A message Matrix did not accept is retried on the next tick rather than marked as sent. Large-transaction checks
  run after every successful sync, whether the scheduler, Sync now or a new connection started it.
- Still to come: new payees taking direct debits, bill rises, card payments abroad (step 8), and the weekly report.

Tests: `apps/server/test/notify.test.ts`.
