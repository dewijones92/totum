---
title: Read-only access everywhere
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0002 · Read-only everywhere

## Context

Dewi: "I'd quite like this to all have just the non-write scope / perms." The app's job is to show money,
never to move it.

## Decision

- Banks and cards: Open Banking **account information** consents only (balances, transactions, details) via
  Enable Banking; payment consents are never requested.
- Trading 212: the existing read-only API key.
- Monzo: its own API. Its token has no scopes and could move money between pots; Dewi accepted that
  ("I don't really use Monzo"), so it is the one exception, and Aerarium only ever calls its read endpoints.
- Savings, pensions, the mortgage and the childcare account are typed in by hand: no credentials at all.
- Gmail is not used by the app. The one-off inventory sweep (2026-10-29) used a full-access app password, so
  giving the app that credential would break this rule.
- Every connector talks through an HTTP client built with an explicit list of allowed methods and paths;
  anything else throws before a request leaves the process, and a test pins every connector's list.

## Consequences

- A compromised server can read Dewi's finances but cannot pay anyone (apart from Monzo pot moves).
- Re-consenting every 90 days is the cost of Open Banking, so the health dashboard tracks expiry.
