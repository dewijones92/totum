---
title: A friend's Chromecast fetches home-only items from the home server, by a link scoped to one cast session
kind: adr
status: accepted
updated: 2026-11-28
---

# 182. A friend's Chromecast fetches home-only items from the home server, by a link scoped to one cast session

- Status: Accepted
- Date: 2026-11-27
- Amends: [ADR 90](0090-a-token-per-device-and-none-in-links.md) (one exception to "no link carries a token": a per-session
  cast link), [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md) (casting judges "away" by a probe of the
  Pi's own address, not by its unmetered-and-answering rule)
- Builds on: [ADR 68](0068-cast-a-stream-the-phone-serves.md), [ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)
  (one casting brain), [ADR 170](0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md) (whose "away from
  home" section named the access-control problem this solves for a Chromecast)
- Does not change: ADR 170's browser target (still home-network only), the Pi's own targets, the phone's casting at home

## Context

Dewi, 2026-11-20, and he took every recommendation: at a friend's house his phone should drive the friend's Chromecast
through the phone's normal Cast session (found on the friend's Wi-Fi), and what only home can serve (a Jellyfin film, a
torrent at home, a home copy) should be fetched by that Chromecast **from the Pi over the internet**, not relayed through his
phone's data. Until now the phone served every cast itself ([ADR 68](0068-cast-a-stream-the-phone-serves.md)): it fetched a home item
from the Pi with the device token, then served it to the Chromecast over the friend's Wi-Fi. That works, and costs the phone the
download and the upload, in the friend's living room, on whatever plan it has.

A Chromecast cannot send the `X-Totum-Token` header, and [ADR 90](0090-a-token-per-device-and-none-in-links.md) forbids a token in a link
(a leaked link was once the master key). The Pi's cast server answers per-session random paths with no token, which is safe
only because it is on the home LAN ([ADR 170](0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md): "the home
network is the gate", and "never publish this port"). So something between had to be designed.

## Decision

### What serves the stream: the Pi's one brain, behind a gate of its own

The stream is built by the casting brain the Pi already runs for its own TVs (`CastStreamServer`,
[ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)): the same rung choice, manifest, relayed or remuxed
picture and processed sound. A session is opened with the new `CastStreamServer.openFor` (the session token without an address) and
read through `CastStreamServer.serve`. **No second brain.**

What is public is **not** the brain's port. Three ways were weighed:

| Way | Verdict |
|---|---|
| nginx forwards `/cast-out/...` to the brain's port 8771 | Rejected. 8771 also serves the browser page, `/screen/*` and the YouTube relay (`/y/`), which ADR 170 says must never be public. One widened `location` or a typo would publish them, and the brain's own session tokens are 64 bits and never expire. |
| A new route on the token-gated 8770 listener | Rejected. It is behind the device token, which is the thing a Chromecast cannot send. |
| **A separate listener on its own port (8773) that serves only live links, and hands each request to the brain in-process** | **Chosen.** Not published to the LAN (only nginx on `bin_private` reaches `totum-fetch:8773`). Nothing but `/cast-out/<link>/<file>` exists on it, so a mistake in nginx can expose a cast and nothing else. The tokens, expiry and revocation live in front of the brain, and the brain is unchanged for the TVs. |

`PublicCasts` (`cli/serve/cast`) is the gate; `PublicCastListener` is its listener (the same `CastHttpServer` class the brain uses, so
the CORS headers a Chromecast needs are the ones the phone's stream already sends). It is off unless `TOTUM_CAST_PUBLIC_BASE` is set.

### The link

`https://totum.<domain>/cast-out/<64 hex>/<file>`, where `<file>` is one of exactly the files a cast session has: `manifest.mpd`, the HLS
playlists, `audio/init.mp4`, `audio/<n>.m4s`, `video`, `video/init.mp4`, `video/<n>.m4s`, `video/r<n>`, `subtitles/<n>.vtt`. The
manifest names everything by relative path (`audio/init.mp4`, `video/r0`, ...), so every address a Chromecast follows resolves under
the same `/cast-out/<link>/`, absolute, https, on the public host (a test resolves them).

- **256 bits** (32 random bytes from `SecureRandom`). The brain's own 64-bit session token is never shown to the internet; the gate
  maps link to session.
- **The Pi keeps only SHA-256 of a link.** A request is hashed, found by the hash, then compared with `MessageDigest.isEqual`
  (constant time), so neither a map lookup nor a string compare is a timing oracle for the link's bytes.
- **Six hours** (`CastOutPaths.LIFETIME_MS`), then 404 and the stream behind it released. A sweep each minute releases a link nobody
  asked for again.
- **Revoked when the cast ends:** the phone's `CastStreams.stopAll()` (the Cast session ending) asks the Pi to end every link it
  minted; minting a second link for an item ends the first. Each link has a separate 128-bit **session id** that only the minting phone
  holds, used to revoke, so revoking needs no link and a link cannot revoke.
- At most **8** public sessions at once; the oldest is ended first.
- A link's session id and the link itself are never logged whole: the Pi, nginx and the phone log the first six characters.

### Minting

`POST /fetch/cast-out/session`, through the existing `totum-auth` gate (the device token, as every other call), with a
`CastOutStart` (the item as the phone's cast would have served it: id, address, separate audio, listening, length, the Chromecast's
model, the boost). The Pi:

1. refuses an address that is not one of **three home-server routes** and says why (`HomeOnlySources`: `/jellyfin/stream/<id>`
   becomes Jellyfin's own address on the Pi; `/ts/...` becomes TorrServer's own through the existing `TOTUM_REWRITE`;
   `/fetch/files?id=` becomes the file the Pi holds). Anything else, any other host, any other path is refused, so a device token cannot be
   used to make the Pi fetch an arbitrary address;
2. opens a session on the brain for a TV described by the Chromecast's own model (`CastScreen.of`), so the rung and codec choice are
   the phone's own, and names the session `cast-out|<id>|<item>` so releasing one never releases another;
3. returns `CastOutAnswer`: `public` with the link, the session id and the expiry, or `phone` with the reason. **Every failure is
   `phone`**: the phone then serves it as it always did.

`DELETE /fetch/cast-out/session?id=<session id>` revokes.

The phone side is `PublicCastRoute` (in `:core:cast`, pure JVM), asked by the Cast converter (`CastStreams.urlForChromecast`) before the
phone's own server. The PC screen did not take this route when this ADR was written (it kept `CastStreams.urlFor`); it does since the
amendment of 2026-11-28 below. The debug stand-in still keeps `CastStreams.urlFor`, the phone's own server only: it is a test harness, not a
cast target ([ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md): a deliberate difference, recorded here). Its
`HttpPublicCastApi` runs the call off the calling thread with a bound (2.5 s to connect, 4 s to read), because the Cast converter
runs on the main thread.

### Away from home

ADR 169's `atHome` (the Pi answered, on an unmetered network) is **true on a friend's Wi-Fi**, so it cannot decide this. The phone
instead **asks the Pi's own LAN address** (`GET http://<the cast address>/lan-check`, which answers `totum-home`, 700 ms limit,
verdict kept 20 s): the phone is on the home network only if the Pi answers there. The address is the `browserAddress` the Pi already
sends with `GET /targets`, remembered by `HomeLan` (kept in prefs). A friend's network that has a `192.168.0.19` which is not the Pi
does not answer with the signature. **Unknown (no address learnt yet) is treated as "serve it as before"**, which is the old behaviour.

The route choice, in order, each logged with the reason (`PublicCastRoute.pick`): an item that is **not home only** (a YouTube stream, a
podcast on the internet, a file on the phone) stays with the phone; not signed in; the phone is on the home network; the home network
is not known; then the Pi is asked; a Pi that refuses, errors or times out leaves it with the phone.

**YouTube items keep the phone-direct path** (decided, and logged as "does not come from the home server"): the phone resolves them
with its own sign-in and network, and the Pi's address is the one YouTube has bot-blocked. Only items whose address is on the home
server, or whose id is a Jellyfin id (`HomeOnly.why`), take the new route.

### What the phone does not do on this route

- **Skip silence is off**: the Pi would cut the sound and the TV's clock would run behind the episode's, and the phone has no timeline
  to map it back (`TimeMappedCastPlayer` reads the phone server's). The request always says `skipSilence=false` and the phone logs
  that it left it off. The boost applies (it is the Pi's `PcmChain`, as for the Pi's own targets).
- **A live manifest is not carried** (the gate does not serve `live.mpd`); a live item stays with the phone.
- No setting: the route is on, and any failure falls back by itself. A switch is cheap to add if Dewi wants one.

## Threat note

The one thing this adds is a path on the public internet that serves media without the device token. What it can and cannot do:

| Threat | What stops it |
|---|---|
| Guessing a link | 256 bits. A client that makes 20 unknown-link or outside-path requests a minute is answered 429 for a minute (`AttemptLimiter`, per `X-Real-IP`); nginx rate-limits the location (30 r/s, burst 100). |
| A link that leaks (a log, a screenshot, a shared message) | It reaches **one session's files only**, for at most six hours, and dies when the cast ends. It cannot read anything else, mint, revoke, or reach another session; a test asks every route the Pi has with a live link in hand. The same exposure the LAN links have, scoped to one item and a time. |
| The link in a log | The Pi's logs and the phone's show six characters. nginx writes the cast-out location to its own log with the link cut to six characters, and a catch-all `location /cast-out/` keeps a path outside the allow-list out of the default log (found by running the real nginx: without it the default log held the whole link). Not controlled: Google Play services, which may log a media item's address on the phone, and the friend's router (which sees the host, not the path, since it is https). |
| Using the mint route to make the Pi fetch an arbitrary address (SSRF) | `HomeOnlySources` allows three route shapes on the home host only. The public listener has no mint route; nginx's `/cast-out/` locations do not reach `/fetch/`. |
| Path tricks (`..`, encoded dots, `//`, a query, another route) | The gate matches the whole path against one anchored allow-list and builds the inner request from the validated parts; nginx repeats the allow-list. A request that does not match is a 404 and never reaches the brain. |
| Using a link after the cast ended or after six hours | Removed from both maps (and the brain's session released). Tested through the unit and over real sockets. |
| Telling a real link from a made-up one | An unknown link, an expired one, a revoked one and an outside path all answer the same 404 body; a CORS preflight answers 204 for both. |
| Someone with a device token starting many casts | At most 8 public sessions; each costs the Pi an ffmpeg decode and encode. A device token already lets its holder stream any home item. |
| nginx misconfigured to forward more | The listener behind it serves nothing but links. |
| Setting `X-Real-IP` to dodge the miss limit | nginx overwrites it, and only nginx (on `bin_private`) reaches 8773; a container on that network could spoof it, and a 256-bit link is not found by guessing at any rate. |
| The bandwidth | The Pi relays the picture, roughly 5 to 10 Mbit/s of upload per playing cast ([ADR 170](0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md), item 3); accepted. |

Residual: whoever holds a live link can watch that one item until it is ended. That is what the exception is.

## Amends ADR 90

[ADR 90](0090-a-token-per-device-and-none-in-links.md) says no link carries a token. This one carries a **per-session secret, not a
home-server token**: it is not the device token, it opens one session only, it expires, and it is revoked by the cast ending. The device
token is still never in a link and still gates the mint. ADR 90 gets a short amendment pointing here.

## Consequences

- At a friend's house a Jellyfin film, a torrent at home or a home copy plays on the friend's Chromecast with the phone doing nothing
  but control; at home, with YouTube, and with no home server, nothing changes.
- The Pi needs three things from Dewi: nginx (the patch under `tools/handoff/cast-out/`), `TOTUM_CAST_PUBLIC_BASE` in `fetch.env`, and a
  release of the CLI that has this change.
- The phone's Cast converter now makes one HTTP call, on a worker thread with a 7 s ceiling (plus the 0.7 s home-network probe), for a home-only item when the phone is
  away. The main thread waits for it, as it waits for nothing else in the converter; a slow home server costs up to that wait once per
  item and then the phone serves the item itself. Pre-minting before the hand-over would remove the wait (a todo).
- `:core:cast` gained the serialization plugin for the wire types.

## Amended 2026-11-28: the PC screen takes the public route too

Dewi, 2026-11-28: a browser at a friend's should play a home-only item (a Jellyfin film, a torrent, a home copy) through the Pi's public link,
the way the friend's Chromecast does. The Pi side was already live (`TOTUM_CAST_PUBLIC_BASE=https://totum.333133333.xyz/cast-out`, the gate on 8773
answering) from 2026-11-28 02:10.

- **One decision, two askers.** `PublicCastRoute.routedStream(item, model, boost, audience, phoneDirect)` is the single place that turns
  "public pick, else the phone's own address" into a `RoutedStream(url, direct)`; the Cast converter (`CastStreams.urlForChromecast`,
  audience `CHROMECAST`) and the PC screen (`CastStreams.urlForScreen`, audience `PC_BROWSER`, handed to `ScreenPlayer` by `PcScreens`) both call it,
  so the home-network probe (`HomeLanProbe`), the home-only test (`HomeOnly.why`), the mint call and the fallback are not written twice. A browser
  fetches cross-origin exactly as a Chromecast does, and the gate already sends the CORS headers, so the Pi needed no change.
- **The PC screen page had to change as well, and that was the part a route-only change would have missed.** `screen.html` rewrote every stream
  address onto its own origin (`location.origin + pathname + search`), because the phone's address inside the manifest URL is its LAN address, which the
  browser may reach only through `adb forward` or the Pi's tunnel relay. Handed `https://totum.<home>/cast-out/<link>/manifest.mpd` it asked the phone's
  page server for `/cast-out/<link>/manifest.mpd`, got a 404 and Shaka error 1001. Seen on the real page against the real gate
  (`MODE=phone tools/cast-bench/screen/public-screen.sh`: `404 /cast-out/…/manifest.mpd` twice, then `page reports error: load: Shaka Error 1001`).
  The `load` command now carries `"direct":true` when the stream has its own origin, and the page then hands Shaka the address as given. Every other
  load (a phone stream, the Pi's own browser target) is rewritten as before. The `load` JSON is built by one function, `loadCommand`, now shared by
  the phone's `ScreenPlayer` and the Pi's `BrowserControl` (they had two copies); `direct` is absent, not false, when it does not apply.
- **The page's own address flow is unchanged and still makes sense.** The page is still served by the phone at its Wi-Fi address and found through
  the Pi bookmark (ADR 81, ADR 93); only the media moves to the Pi. At a friend's the PC must therefore be on the same network as the phone (the
  bookmark's 302 goes to the phone's friend-LAN address, a 192.168/16 shape the Pi accepts), or the page cannot reach the phone's `/screen/*` calls.
  A page from `http://<phone LAN address>` fetching `https://totum.<home>/…` is not mixed content (http to https), and nothing is fetched from a
  private address by a public page, so Private Network Access does not apply. A PC on another network than the phone is not served by this.
- **No model is sent for a PC.** The mint request's `model` is `null` for a screen, not the page's name (`PC (Edge)`): `CastScreen.of` reads a model
  string for `webOS` and LG series names, and a PC named that way would be given the LG's profile. The Pi therefore builds the stream for an "unknown
  Cast device" (VP9 and H.264, up to 1080p, H.264 first, ADR 179), which every browser plays. The screen's own reported codecs and height are not
  passed to the Pi: that needs a new field on `CastOutStart` and a Pi release. Not built.
- **Same limits as the Chromecast route:** skip silence is off, live stays with the phone, YouTube keeps the phone-direct path, home network and an
  unknown home network keep the phone's own address, and any refusal or silence from the Pi falls back to the phone's own server with the reason logged.
  A reload of the same item (a new `load`) mints a new link and ends the earlier one for that item; "📱 Back to this phone" ends every link
  (`stopAll`). Moving to the next item leaves the previous item's link until the cast ends, the cap of eight, or six hours, as for a Chromecast.
- **Logs.** The `cast-out` lines now name who fetches: `the PC's browser fetches it from the home server (session xxxxxx…, model unknown, …)`, or
  `the phone serves it to the PC's browser, because …` (a Chromecast says `the friend's Chromecast`). `[screen]` says
  `<id>: load N sent to the screen from the home server's public link` or `from this phone`.
- **Evidence.** `PcScreenRouteTest` (7) and `ScreenLoadCommandTest` (3), seen failing first against a `routedStream` that always answered with the phone's
  address and a `loadCommand` that ignored `direct` (`AssertionError: http://192.168.1.9:8765/c/phone-token/manifest.mpd` where the public link was
  expected; `the page ignores a direct stream`). End to end, `tools/cast-bench/screen/public-screen.sh`: the real `totum serve`, the real `screen.html`
  from a stand-in for the phone's page server, and a headless Edge on Windows: the page said hello, got a `direct` load, Shaka played the 12 s H.264/AAC
  film at 640x360 from the gate on another origin (`time` 0.9, 3, 5, 7, 9 at 2 s steps), the gate logged manifest, both inits and the segments for
  one session, and ending the cast revoked the link (`8 request(s), 420KB served`).
- **Not proven.** A real phone, a real PC at a friend's house, the real Pi over the real internet with TLS (the stand-in used plain http on loopback),
  and the Cast converter's and the screen's call on a device.

## Not proven (read before relying on it)

- **A real Chromecast fetching over the real internet, and the real Pi's nginx.** Not run: nothing was changed on the Pi. What ran is
  below.
- **The Cast converter's call on a phone.** `CastStreams` is Android glue; the decision, the client, the wire and the Pi side are tested
  on the JVM, the glue compiles and passes lint, and no device ran it.
- **TLS and HTTP/2 to a real Chromecast.** The wildcard certificate is the Pi's; the local nginx ran plain http.
- A Chromecast's own behaviour on a 429 or a slow upstream.

## Evidence

Run on this laptop, 2026-11-27, against the real `totum serve` (`cli/build/install`), a generated H.264/AAC film behind a fake Jellyfin,
and, in the second run, a real local nginx built from the handoff files:

- `tools/cast-bench/standin/public-standin.sh`: the phone's own mint request returned `https://totum.test/cast-out/<64 hex>/manifest.mpd`;
  Shaka in a private headless Edge (page `about:blank`, so every request is cross-origin) loaded the manifest in 371 to 396 ms
  (`avc1.42C01E/mp4a.40.2 640x360`, 12 s), started in 716 to 1136 ms, and sought to 5 s in 556 to 560 ms with no player errors; the gate
  answered 404 for `/`, `/screen`, `/lan-check`, `/targets` and a made-up link; ending the cast answered `revoked:true` and the link
  then answered 404. The server logged the link as six characters and the session as "109 request(s), 590KB served over 5s, asked for
  audio×102, manifest×3, video×4".
- Through nginx (`nginx -c` from the patch and snippet, a server-level `auth_request` that refuses everything standing in for
  `totum-auth`): `/` 401; the link's manifest 200 with `application/dash+xml`, `Access-Control-Allow-Origin: *` and `no-store` passed
  through; a ranged video segment 200; POST on the link 403; a made-up link 404; a path outside the allow-list 404; `/cast-out/session`
  404 (the mint route is not public); 100 of 200 simultaneous requests answered 429; the dedicated log held 113 lines and none held the
  whole link; the default log held 0 (it held 1 before the catch-all `location /cast-out/` was added). The first attempt at the patch
  did not load (`unexpected "{"`: an unquoted regex containing `{6}`), which only a real nginx could say.
- Unit and end-to-end tests: `PublicCastsTest` (18), `CastOutEndToEndTest` (10), `HomeOnlySourcesTest` (6), and in `:core:cast`
  `PublicCastRouteTest` (10), `HomeLanProbeTest` (7), `HomeOnlyTest` (5), `HttpPublicCastApiTest` (6), `CastStreamServerOpenForTest` (5).
  Seen red by neutering the expiry check, the allow-list and the removal of a revoked link: "expected:<404> but was:<200>" in the
  expiry, revoke and path tests (`screen expected:<404> but was:<200>`).

## Amended 2026-11-28, later: choosing the public route releases the phone's own serving of the item

`CastStreams.urlForChromecast` and `urlForScreen`, when the pick was the Pi's public route, left `CastStreamServer`'s entry for the item
(`latest[mediaId]`, with its silence timeline) from an earlier phone-direct serving of the same item. After a home to away switch
mid-cast, `TimeMappedCastPlayer` reads `timelineFor(mediaId)`, so it could map the TV's position through a timeline of cuts the TV was
no longer playing (the public route never cuts silence).

- `routedStream` gained an `onPublic` hook, run once when the pick is public; `CastStreams` passes `server.release(mediaId)` through it
  for both the Chromecast and the PC screen, logging `dewidebug <id>: the <audience> audience takes the home server's route, so the
  phone's own serving of it (and its silence timeline) is released`, and `release` logs its own `released N session(s)` line.
  Releasing also closes the phone's engine for that item and forgets its stall and failure state, none of which a public cast uses.
- The phone route is untouched: choosing it releases nothing.
- Test: `PublicRouteReleasesPhoneServingTest` (2), a real `CastStreamServer` served phone-direct with silence cut, then the route
  switched to public. Red first: `a stale timeline would map the TV's clock through cuts it is not playing expected null, but was:
  <CastTimeline@…>`. The other test pins that the phone route releases nothing.
- Not run on a phone: `CastStreams` is Android glue; it compiles, the decision and the server are tested on the JVM.
