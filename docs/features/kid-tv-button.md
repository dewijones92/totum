---
title: Kid TV: one tap puts the kid playlist on the TV
kind: feature
area: casting/kids
status: built 2026-11-07, Batch A (tests owed; not run on a TV)
updated: 2026-11-07
---

# 👶📺 Kid TV

Decision: [ADR 148](../adr/0148-kid-tv-is-kid-mode-on-the-tv-and-a-pinned-playlist.md). Builds on kid mode
([ADR 76](../adr/0076-kid-lock-pins-the-app-to-a-bare-video.md)) and casting ([casting](casting.md)). Backlog item:
[todos/kid-tv-button](../todos/kid-tv-button.md).

## What a person sees

- **Library → the 👶📺 Kid TV tile** (above Pinned). Set up, it reads "▶️ <playlist> on <TV>"; one tap casts that pinned
  playlist to that TV in kid mode and the tile says "✅ <playlist> on <TV> · tap to stop". A tap while it is on pauses
  the TV and ends the cast. Not set up, it reads "⚙️ Choose a playlist and a TV" and opens the page. The gear opens the
  page whatever the state.
- **The page** (also Settings → 👶📺 Kid TV, searchable): the status, a Start / Stop button, **Playlist** (every pin
  that can show a picture, one chosen) and **TV** (🔍 Look for TVs lists what is advertising itself for casting; the
  chosen one stays listed).
- **Kid mode on the TV:** 1× speed and no silence skipping whatever a show's own settings say, the playlist (or the
  item, when it has no group) loops, auto-play on, Explore off, and no sleep timer is armed by bedtime. The phone is not
  locked and carries on as the adult's phone; the ADR 76 lock is a separate door to the same rules.
- The start is a task in the progress strip ("⏳ Starting <playlist> on <TV>", Cancel) and ends with a toast; a failure is
  said in the tile and the page: ❌ playlist no longer pinned, ❌ the TV isn't showing up (with what was seen), ❌ Android
  hasn't given local-network access (tap Cast once), ❌ casting didn't start, ❌ the playlist wouldn't start.
- The cast ending (the TV turned off) or moving to another TV leaves kid mode by itself.

## Seams

`kidlock/KidTv` (the orchestrator and its ports), `KidLock.tv`/`modeOn`, `KidTvChoice`/`KidTvStore` (own preferences,
Drive backup key `kidtv.v1`), `KidTvWiring` (ports from the container), `cast/MediaRouterTvConnector` (find a route by
name, select it, wait for the session), `CastTarget.device`, `PinPlayer.play` (the playlist), `ui/kidtv/KidTvScreen` and
`KidTvText`, `ui/library/KidTvTile`.

## Diagnostics

`kidlock` lines: `Kid TV loaded: …`, `starting "<title>" (<pin key>) on <TV> from <why> [castDevice=… castingThere=…]`,
`waited for the cast hand-over … settled=…`, `kid TV mode on for <TV> from …`, `Kid TV is on: …`, `start from … failed:
<why>`, `the cast moved off <TV> …`, `stopped (<why>): was <status>, ending the cast too=…`, `kid mode is now on|off (…)`.
`cast` lines: `looking for cast TVs: …`, `selecting the cast route …`, `no cast route named … within 20000ms; routes seen:
[…]`. The report's state block carries `kidTv.pin`, `kidTv.tv`, `kidTv.pinStillPinned`, `kidTv.status`, `kidTv.castDevice`.

## Not done / needs a TV

- Not run on a real TV: no device was touched while building it. The first check, daytime, on the LG with a tennis
  playlist and the existing standing permission (low volume, restored): the route selects by name, the hand-over wait is
  long enough, the loop repeats, stopping hands back to the phone paused.
- Totum does not wake the TV; it must be on or in a state where it still advertises itself.
- A pause from the phone's own Exsurge, alarms or recorder also pauses the TV; whether Kid TV should hold those off is
  Dewi's call.

## Tests owed

A Compose test of the tile and page; `MediaRouterTvConnector` against a fake router; a debug broadcast to start Kid TV
for a device run. `KidTvTest` (13 cases) covers the orchestrator.
