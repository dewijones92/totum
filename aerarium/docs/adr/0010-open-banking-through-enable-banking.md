---
title: Open Banking through Enable Banking, own accounts only
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0010 · Open Banking through Enable Banking

## Context

UK banks and cards can only be read by a regulated account-information provider. GoCardless Bank Account Data,
the usual free route for self-hosted apps, stopped taking new signups in 2025. Enable Banking's API reference
says a production application is activated "after you whitelist your own accounts", which is the personal-use
route. Dewi agreed to an aggregator after the inventory (2026-10-31).

## Decision

- One Enable Banking application, production, restricted to Dewi's own accounts. Requests are JWTs (RS256,
  `kid` = application id, one hour) signed with a private key generated on the Pi; the key lives in
  `/var/lib/aerarium-keys/` ([ADR 0007](0007-secrets-encrypted-key-outside-backups.md)).
- Consent asks for the shorter of 90 days (`EB_MAX_CONSENT_DAYS`) and the bank's maximum, for
  `psu_type: personal`. The callback state is random, single-use and forgotten after a day.
- Reconnecting the same bank updates the existing connection (new session and expiry, same accounts and
  history) rather than creating a second one; the health tile's 🔑 Reconnect starts it.
- Syncs run every 6 hours and retry a failure no sooner than 6 hours later, which keeps within the UK limit of
  four unattended reads a day.
- Balances prefer booked types (`CLBD`, then `ITBD`, `CLAV`, `ITAV`, …). Only booked transactions are stored;
  pending ones are counted in the log and picked up once booked. A transaction without the bank's own reference
  gets a stable hash id. The first sync asks for two years; later ones from ten days before the last.
- Every newly found account is flagged until Dewi says whose it is (CLAUDE.md, "Joint is always labelled").

## Consequences

If Enable Banking lacks a bank, or its terms cap personal use, the fallback is a bank's own read-scoped token
or CSV import. Nothing else depends on Enable Banking.
