---
title: The Pi's health page may read the health report
status: accepted
date: 2026-11-01
updated: 2026-11-01
---

# 0011 · The Pi's health page may read the health report

## Context

Dewi, 2026-11-01: a health dashboard in his private area for the Pi's services "and also health of the money app …
that has a health section … just reference that somehow … keep this dry". He chose a summary tile with a link to
Aerarium's own health page. Aerarium accepts connections only from nginx and only the owner's email (ADR 0005), so
the dashboard's server cannot ask it, and widening either rule for that would weaken the money app.

## Decision

The dashboard page, running in the owner's signed-in browser, reads `GET /api/health` itself. Aerarium adds CORS
headers (origin, credentials, `Vary: Origin`) to that one path, and only when the request's `Origin` is exactly
`HEALTH_READER_ORIGIN` (unset = off). Every guard still applies: the request comes through nginx's owner gate with the
owner's Google session, and a non-owner is refused as before. Aerarium still judges its own health; the dashboard
shows `overall` and the sources that are not ok, and links here.

## Consequences

- No other path is readable cross-site, and no other site can read the report.
- The deployment sets `HEALTH_READER_ORIGIN=https://health.<domain>` in the Pi's compose (dot-files).
