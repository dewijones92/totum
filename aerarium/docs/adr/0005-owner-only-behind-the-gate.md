---
title: Owner only, behind the Google gate
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0005 · Owner only, behind the gate

## Context

Aerarium sits behind the Pi's oauth2-proxy + nginx `auth_request` gate, like per-chordas-ad-astra. Since
2026-10-30 that gate admits more than one Google account (per-chordas ADR 0019), so "signed in" no longer
means "Dewi".

## Decision

- The server answers only requests from the trusted nginx network (`TRUSTED_CIDRS`); it publishes no host port.
- nginx passes the signed-in email as `X-Forwarded-Email` (always overwriting whatever a browser sent).
  With `OWNER_EMAIL` set, any other email, or none, gets `403 🔒 Aerarium is private to its owner.`
- `/healthz` stays open (it carries only `ok`, the version and the scheduler's last tick) so deploys can check it.
- With `OWNER_EMAIL` unset (dev and e2e) the check is off, and the startup log says so.

## Consequences

Dewi's household can share the gate without seeing his finances. Joint accounts are still shown, to Dewi, tagged as joint.
