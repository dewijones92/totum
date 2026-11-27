---
title: Each device has its own home-server token, and no link carries one
kind: adr
status: accepted
updated: 2026-11-27
---

# 90. Each device has its own home-server token, and no link carries one

- Status: Accepted
- Date: 2026-10-24
- Amended 2026-11-09: the token is judged on every hop of a redirect
- Amended 2026-11-27 by [ADR 182](0182-a-friends-chromecast-fetches-home-items-from-the-home-server-by-a-session-link.md): one
  exception, a per-session cast link (the last section)

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

## Amendment, 2026-11-09: the token is judged on every hop of a redirect

OkHttp keeps custom headers when a call is redirected to another host (it strips only `Authorization`), and the app's
image and transfer clients added the token in an application interceptor, which runs once per call, so a redirect from
the home server to another host carried the token with it. The one place that adds it is now
`OkHttpClient.Builder.sendingTheHomeToken()` (`HomeServerAuthInterceptor`, a network interceptor, so it sees every
hop): it adds the token to a request for the home server's hosts and takes it off a request for any other host, even
one that already carried it. Both clients use it. `HomeServerAuthInterceptorTest` runs a MockWebServer redirect to
another host and asserts the header is absent there (seen red against the application-interceptor wiring). Not
covered: Media3's `DefaultHttpDataSource` follows redirects itself with the headers `homeServerAware` gave the first
request; no redirect from the home server is known, so that path is recorded here and not changed.

## Amendment, 2026-11-27: one exception, a link scoped to one cast session

Dewi's casting from home to a friend's Chromecast ([ADR 182](0182-a-friends-chromecast-fetches-home-items-from-the-home-server-by-a-session-link.md))
needs the Chromecast to fetch from the Pi over the internet, and a Chromecast cannot send `X-Totum-Token`. The rule is kept for the device
token and amended for this one case: **a link may carry a per-session secret that is not a home-server token.**

- It is a random 256-bit value the Pi minted for one cast session, in the path (`/cast-out/<64 hex>/<file>`), never in a query.
- It opens that session's files and nothing else; it is valid six hours at most and is revoked when the cast ends.
- It is minted only through the device-token gate (`POST /fetch/cast-out/session`), and revoked by a call with the session id,
  which is not the link. The device token is still never in a link, a log or an address.
- The Pi keeps only its SHA-256, compares in constant time, and shows six characters of it in any log.

A leaked cast link is therefore refused after the cast and once its time is up, and while it lives it is one item's stream, not the
master key the shared token once was. The threat note is in ADR 182.
