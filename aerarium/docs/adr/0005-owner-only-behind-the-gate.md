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

- The server answers only requests from nginx itself: `TRUSTED_HOSTS=nginx_dewi` is resolved through Docker's DNS
  (cached 30 s, re-checked when an unknown address appears, refused if it does not resolve), plus loopback in
  `TRUSTED_CIDRS`. It publishes no host port. (Until 2026-10-31 it trusted the whole `bin_private` subnet, which
  16 containers share, so any of them could have sent a forged `X-Forwarded-Email`; found by review. A spoofed
  source address cannot complete a TCP handshake, so pinning the address is enough without a shared secret.)
- nginx passes the signed-in email as `X-Forwarded-Email` (always overwriting whatever a browser sent).
  With `OWNER_EMAIL` set, any other email, or none, gets `403 🔒 Aerarium is private to its owner.`
- `/healthz` stays open (it carries only `ok`, the version and the scheduler's last tick) so deploys can check it.
- With `OWNER_EMAIL` unset (dev and e2e) the check is off, and the startup log says so.

## Consequences

Dewi's household can share the gate without seeing his finances. Joint accounts are still shown, to Dewi, tagged as joint.
