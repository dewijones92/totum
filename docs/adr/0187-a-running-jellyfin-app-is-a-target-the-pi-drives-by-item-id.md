---
title: A running Jellyfin app is a target the Pi drives by item id, and it takes only Jellyfin items
kind: adr
status: accepted
updated: 2026-11-20
---

# 187. A running Jellyfin app is a target the Pi drives by item id, and it takes only Jellyfin items

- Status: Accepted
- Date: 2026-11-20
- Builds on: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (targets and decks),
  [ADR 154](0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) (the Pi's Jellyfin key and `jellyfin:<id>` items),
  [ADR 174](0174-dlna-and-airplay-are-more-controls-behind-the-one-cast-player.md) and
  [ADR 175](0175-dlna-airplay-and-kodi-carry-the-picture-over-the-one-stream-servers-hls.md) (`RendererWatch`, the shared polling
  machine of Kodi and DLNA), [ADR 177](0177-a-device-says-how-it-comes-on-and-goes-off-lg-over-webos-kodi-started-over-adb.md)
  (how a device says it is on or off), [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md) (recorded exception)
- Backlog item: [jellyfin-remote-control](../todos/jellyfin-remote-control.md)

## Context

Dewi, 2026-11-20: drive what the Jellyfin app on the Fire Stick or the LG (or the web client) is playing from Totum, as one more
target beside the speakers and TVs, with the same queue, colour, volume and power state. Jellyfin's own Sessions API does this:
`GET /Sessions` lists every client that has signed in, `POST /Sessions/{id}/Playing` hands one an item to play,
`POST /Sessions/{id}/Playing/{command}` pauses, resumes, seeks and stops it, and `POST /Sessions/{id}/Command` sets its volume.
The Pi already holds a Jellyfin API key (`TOTUM_JELLYFIN_URL`, `TOTUM_JELLYFIN_KEY`, ADR 154).

Read from the Pi's Jellyfin on 2026-11-20 (version 12.4.0, its own `/api-docs/openapi.json`, read only):

- `POST /Sessions/{sessionId}/Playing` takes `playCommand` (`PlayNow`, `PlayNext`, `PlayLast`, `PlayInstantMix`, `PlayShuffle`)
  and `itemIds` (Jellyfin item ids), `startPositionTicks`, `mediaSourceId`, `audioStreamIndex`, `subtitleStreamIndex`,
  `startIndex`. **There is no URL parameter** on it, on `/Playing/{command}` (a play state command and `seekPositionTicks`), or
  on `/Command` (a `GeneralCommand` name and string arguments; the list has `PlayMediaSource`, `DisplayContent` and `Play`, all
  about library items). Nothing in the server's API tells a client to play an arbitrary address.
- A session carries `SupportsMediaControl` and `SupportsRemoteControl` at its top level. They are false for a client that is
  listed but has no live connection to the server; the one session listed on 2026-11-20 (a phone app, last active the day
  before) was exactly that, with `Capabilities.SupportsMediaControl` true beside it. So "listed" is not "running": only
  `SupportsMediaControl` true at the top level means the server can send it a command now.
- `PlayState` carries `PositionTicks`, `IsPaused`, `VolumeLevel`, `CanSeek` and `NowPlayingItem` appears only while something
  plays (`Id`, `Name`, `RunTimeTicks`); ticks are 10,000 to the millisecond. The Pi's `jellyfin-wedge-watch` already relies on
  those fields on real playing sessions.

## Decision

- **A Jellyfin client is a target, in the same deck as every other.** `TOTUM_JELLYFIN_CLIENTS=<label>=<what its device name
  contains or its exact device id>[;...]` (for example `Fire Stick=AFT`; a bare name is both) names the clients that are
  targets. Each becomes a `Target` with id `jellyfin-<label>` and kind `TV` on the wire (no new `TargetKind`: an old phone
  cannot read one, as in ADR 174). They are listed whether or not the app is open, so the picker does not lose them when the
  app is closed; unset, no Jellyfin client is a target and nothing about the Pi changes. It does nothing unless Jellyfin
  itself is configured.
- **The seam is `TargetPlayer`, not `CastControl`, and the polling is `RendererWatch`.** `CastControl.load` takes a URL and
  `ChromecastPlayer` turns the item into Totum's own manifest before it gets there; a Jellyfin app plays an item it already holds
  by id, so there is nothing to build, relay or cast. `JellyfinClientPlayer` is therefore a `TargetPlayer` that sends the id
  (`PlayRequest.itemId`, new, set by the deck), and everything else is the deck's: queue, repeat, resume, volume, sleep timer,
  problems, progress. Polling is the shared `RendererWatch`, with a look that reads `GET /Sessions` through one cached
  `SessionBoard` (so several clients cost one request a second, not one each).
- **What the client reports decides what happened.** The look compares the session's `NowPlayingItem.Id` with the item Totum
  loaded: the same id is playing or paused with its position and length; nothing playing after it was seen playing is the end
  of the item (the queue moves on); another item playing (the app's own "next episode" autoplay) is also the end of ours, so the
  deck, not the app, decides what comes next; never seen playing within 20 s is a failure; three missed looks, a session that
  is no longer listed or an app that is no longer controllable is a lost target. A refused or unanswered `PlayNow` is a failure
  whose reason names the answer (never the key).
- **Power is whether the app is open.** `TargetState.power` is `ON` when Jellyfin lists a controllable session for the client
  and `ASLEEP` when it does not, with `switchedOnByHand` true: Totum cannot open an app on a TV it only knows through Jellyfin
  (starting the Fire Stick's app over adb, as ADR 177 does for Kodi, is possible and left for a follow-up). A play to a closed
  app says "🔌 Switch <label> on, then try again" (`SWITCH_ON`), no new trouble kind (an old phone reading an unknown kind fails).
- **A Jellyfin client plays only Jellyfin items; this is the decision for non-Jellyfin items.** Evidence above: the server's
  API gives a client an item id, never an address, and a YouTube video or a podcast is not an item in Jellyfin's library. So:
  - the Pi **refuses** them at the door: `TargetPlayer.refuses(item)` (default none; `TargetPlays.JELLYFIN_ONLY` for this
    player, one rule in `:core:data` shared with the phone) takes them out of a request before anything is looked up or
    queued; a request with none left is a 422 "❌ Nothing in that was playable on <label>: a Jellyfin app plays only films and
    episodes from the Jellyfin library", a mix keeps its Jellyfin items and logs the rest; a deck restored with something it
    now refuses passes over it as "no stream" with that reason;
  - `TargetState` gains `plays` (`ANYTHING`, the default, or `JELLYFIN_ONLY`; an older phone ignores the field, an older Pi
    omits it);
  - the phone's picker **says so**: "🎞️ Jellyfin films and episodes only", or "🔌 Jellyfin is closed · open it by hand ·
    Jellyfin films and episodes only" while the app is not open (`PlayOnHint.JELLYFIN_ONLY` and `JELLYFIN_NOT_OPEN`);
  - **and falls back**: `TargetsRepository.take` does not redirect a batch to a Jellyfin-only target when any item in it is not
    a Jellyfin item, so it plays on this phone, logged with the reason, the way a file only the phone holds already does
    (ADR 176). A Jellyfin item still goes to the target, and one tap on a YouTube video with a Jellyfin target remembered as
    "Play on" is not an error.
  Not built, because the API cannot do it: playing a YouTube item or a podcast on a Jellyfin app (casting a URL to it). A TV
  that also runs Kodi or has a Chromecast or AirPlay is already a target of its own for those.
- **Recorded as a deliberate exception to ADR 108**: an action offered for every kind of item is not offered here, with the
  reason above.

## Consequences

- **The key.** Every Sessions call is made by the Pi with the key in the `Authorization` header, exactly as the browse
  routes do (ADR 154); the phone never holds it, no address built for these calls carries it, and nothing logged contains it
  (a test records every log line of a whole play, pause, volume and stop). Log lines name the session id and the item id,
  which are not secrets.
- **Not run against a real client.** On 2026-11-20 the Pi's Jellyfin listed one session, a phone app that was not
  controllable, and the Fire Stick app was not running, so no write was sent and no playing session was captured. The fixtures
  are the live idle session (device name, user, ids and times replaced) and a playing session written from the server's own
  OpenAPI schema (marked synthetic); the tests use a fake Jellyfin on a local HTTP server. Whether the Android TV app obeys
  `PlayNow` with `startPositionTicks`, `Seek`, `Pause` and `SetVolume` as the API documents is the first thing to try after
  deploying (the runbook in the backlog item does it with one film).
- **Autoplay.** The app's own next-episode autoplay may start the next episode a moment before Totum's queue does; Totum then
  sends its own next item, which replaces it. If that flickers on the real stick, the fix is to turn the app's autoplay off.
- **A session is found by device name or id, not session id**, because a session id changes every time the app signs in. Two
  sessions of one device resolve to the controllable one, then the most recently active.
- **Cost.** One `GET /Sessions` a second while an item of a Jellyfin target is loaded (shared by every client), one every
  ten seconds per target from the deck's keep-awake pass, and one at each scan of the targets (a minute); nothing otherwise.
- **Volume** is the client's own (`SetVolume`, 0 to 100) and what it reports (`VolumeLevel`); a play never sets it (ADR 152).
- Tests: `JellyfinClientTargetTest` (fake Jellyfin, `FakeJellyfinSessions`), `TargetDeckJellyfinOnlyTest`, `TargetPlaysTest`,
  `TargetsRepositoryTest`; fixtures in `cli/src/test/resources/jellyfin-sessions/`.
