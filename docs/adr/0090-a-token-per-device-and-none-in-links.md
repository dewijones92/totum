---
title: Each device has its own home-server token, and no link carries one
kind: adr
status: accepted
updated: 2026-10-24
---

# 90. Each device has its own home-server token, and no link carries one

- Status: Accepted
- Date: 2026-10-24

## Context

Since 1 August the totum. gate checked one shared token, created once and never rotated, handed to every device by
the Google-gated sign-in page together with Prowlarr's API key. Because the player fetched with its own HTTP stack,
the token also rode in every torrent stream and audio link (`totumToken=`), so a copied link was the master key:
Dewi pasted one on 2026-10-24, and the same token had leaked into a log on 2026-10-21. Dewi asked for the long-term
fix: a token per device, and links that are worthless when leaked.

## Decision

- `server/totum-auth` holds the tokens. nginx asks its `/check` before every request to totum. (`auth_request`, in
  the gitignored `totum-guard.partial`). Signing in at totumauth. (Google-gated, and the only way to reach `/signin`,
  which demands a secret header nginx adds) mints a token for the device the app names; only its SHA-256 is stored,
  with the name and when it was last used. Signing in again on a device retires its old token; a device unused for 30
  days is forgotten; at most 20 devices. `/auth/signout`, `/auth/devices` and `/auth/revoke` answer a device's own
  token; on the Pi, `docker exec totum-auth python server.py devices | revoke <id>`.
- A device token is accepted only as the `X-Totum-Token` header. The app adds it to every home-server request in one
  place (`HomeServerAuth` in `:lib:common`): an OkHttp interceptor for the app's clients, and `homeServerAware` around
  the player's data source, the cast audio path and the cast relay. Stream and audio links carry no token.
- Settings → Home server: sign-in sends the phone's name; "Sign this phone out" revokes its token on the Pi.
- The old shared token keeps working (header or link) until `server.py retire-legacy`, and an app that names no device
  at sign-in is still handed it, so installed builds keep working through the change.

## Consequences

- A leaked link is refused; a lost phone is signed out alone; tokens left unused expire.
- Prowlarr's key still reaches the phone at sign-in; it is only usable through the gate, which needs a device token.
- One subrequest to totum-auth per request to totum. (local, a few milliseconds); if totum-auth is down, totum. is
  refused (it fails closed).
- To finish: once the phone runs a build with this and has signed in again, retire the old shared token.
