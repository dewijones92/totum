---
title: A browser is a target the Pi plays to, found by opening the Pi's address; Play on… says how a device comes on
kind: adr
status: accepted
updated: 2026-11-13
---

# 170. A browser is a target the Pi plays to, found by opening the Pi's address

- Status: Accepted
- Date: 2026-11-13
- Amends: [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md) (the open points: how a device that is
  switched on by hand is worded, and one rule for a device's colour), [ADR 81](0081-the-pc-screen-is-a-page-the-phone-serves.md)
  (the receiver page now lives in `:core:cast` and has a second host)
- Builds the `BROWSER` kind that [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) modelled and never registered

## Context

Dewi, 2026-11-13: a link he can open in any browser, Silk on a friend's Fire Stick for one, that turns that browser into a
receiver; on the existing PC screen page; a short address that is easy to type on a TV remote; in the "Play on…" picker
once a browser has opened it, with a colour like every target. The page had one host so far, the phone
([ADR 81](0081-the-pc-screen-is-a-page-the-phone-serves.md), [ADR 93](0093-the-private-area-opens-the-pc-screen-and-watch-here-takes-over.md)):
the phone serves it, the phone is the brain, and the page is the phone's remote ear. A phone-hosted page cannot be a
*target* with a queue of its own, cannot be played to with the phone off, and has no address a person can type (a 128-bit key
rides in the link's fragment).

## Decision

- **The Pi hosts the same page, and the browser is a Pi target.** The cast server the Pi already runs for TVs (`PiStreams`,
  port 8771, published on the LAN by the compose file) also answers `/` and `/screen*` through the same `ScreenLink` the phone
  uses. A browser that opens `http://<the Pi's LAN address>:8771` says hello (its name, codecs and height, as on the PC
  screen), and `BrowserSource` lists it in `GET /targets` as a `TargetKind.BROWSER` target. Nothing else changed in the
  picker, the chips, the queue, the marks or the colour: it is a target. It is found by *opening the address*, so the picker
  offers it once a browser has opened it and drops it when it has been silent for a minute.
- **One casting brain, again** ([ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)). No second stream
  server and no second player: `BrowserControl` is a `CastControl` over `ScreenLink` (load, pause, seek, volume, stop, the
  page's reports as a reading, an item's end told once, a report about the item before ignored by load number), so the
  existing `ChromecastPlayer` and `CastLink` drive a browser exactly as they drive a TV: the same manifest the phone builds,
  the picture relayed by range, the sound processed (skip silence, boost) by the Pi, the timeline map for a cut item, the
  nudge when cut sound ends before the picture. `PiCastTv` takes the page's own codec profile (`screenProfile`) where a
  Chromecast takes a model name. The page loads the manifest from its own origin, so the stream is same-origin with the page and
  mixed content cannot arise.
- **The page is shared, not copied.** `screen.html` moved from the phone's assets to `:core:cast`'s resources
  (`ScreenPage.bundled()`); the phone and the Pi serve the same bytes. The server marks which host served it
  (`data-host="phone"` or `"pi"`, `ScreenLink`'s `ScreenHost`), and the page words itself for it: on the Pi there is no
  "Watch here", no "On your phone" and no "Switch phone"; it says "In Totum, choose ▶️ Play on, then “<name>”." A page that
  is told to stop shows its waiting screen only after 1.5 s, so the next item of a queue does not flash it. A `volume`
  command was added for the Pi's sleep fade and slider (a browser's volume is `video.volume`, set only by the slider, never by a
  play, as for every device).
- **The short address.** `TOTUM_CAST_BASE` without its scheme (`192.168.0.5:8771`), sent to the phone as
  `TargetsAnswer.browserAddress` (`browserAddressOf`). The phone shows it under **🖥️ Add a browser…** at the foot of the
  picker row and of every item's "Play on…" list, which opens a dialog with the address and 📋 Copy address, only at home (ADR 169's rule) and even when no other device
  is listed. Typing `192.168.0.5:8771` into Silk with a TV remote is the whole set-up; there is no key and no pairing.
- **No key on the Pi's page: the home network is the gate**, as it already is for the TV streams on that port (per-offer random
  paths, no token, because a TV cannot send one). [ADR 90](0090-a-token-per-device-and-none-in-links.md) is kept: no
  home-server token is in any link, and the page never sees one. Anyone on the home LAN can open the page and list a
  browser in the picker, and nothing plays there unless a person picks it. **This is the sentence the remote case below
  breaks, and why it is not built.**
- **Names and ids.** A browser's target id is made from its page id, which the page keeps in `localStorage`, so the same
  browser is the same target (and keeps its queue, `target-<id>.json`) next time. Its label is the browser's name from
  its user agent (Silk, Edge, Chrome, …); two browsers with one name are told apart by the end of their ids. `TargetDeck.relabel` lets a renamed device change
  its label without losing its deck; `TargetDecks.sourcesChanged` makes a new browser appear at once rather than at the next
  once-a-minute look. Nothing in the page lets a person type a name yet (a stored `totumScreenName` is read, nothing writes it).
- **A browser that goes quiet is a lost connection.** It is "reachable" only if heard in the last 10 s; after 30 s of silence
  while it has a deck, `BrowserScreens.sweep` tells the deck (`lost`), which stops, keeps the place and says so, as for a TV.
  The picker may still list it for up to a minute (the target list is re-read once a minute; arrivals are immediate, departures lag).

### Away from home: not built, and what it would need

A friend's network cannot reach `192.168.x.y:8771`, so today a browser has to be on the **same network as the Pi**. The
brief asked for the remote case to be decided: it is **not built**, because it is not cheap and not safe to do quickly.
Everything the page does is already origin-agnostic (it loads `location.origin + path`, never a fixed host), so the work is
all in front of the Pi, none in the page:

1. **A public https name for the cast port**, in nginx on the Pi (the `dot-files` repo, outside this one): a server block for
   a new name, `proxy_pass http://127.0.0.1:8771`, `proxy_buffering off`, and a read timeout above the page's 20 s long
   poll, plus a certificate. Not tested from here.
2. **Access control that is not "being on the LAN".** Published, `/screen/*` and `/c/<token>/` answer anyone on the internet, so a
   stranger could list a browser in Dewi's picker and read the stream URLs of what he plays. The least that is safe is pairing:
   the page shows a 4-digit code, the phone (which holds the device token, ADR 90) approves it through a token-gated
   `POST` on the Pi, and the Pi mints a key for that browser (an HMAC of its id under a Pi secret, so no state to lose)
   that the page keeps in `localStorage` and sends on every `/screen/*` and stream request; `ScreenLink` already has the shape
   (a key per call), it would take a per-id key check and a token on `/c/`. The alternative is the Google gate of the private area,
   which a TV remote makes miserable.
3. **An uplink decision.** The Pi relays the picture, so a remote browser pulls roughly 5 to 10 Mbit/s of 1080p through the
   home connection's upload for as long as it plays.
4. **The picker already allows it**: `TargetKind.BROWSER` is `reachableAway`, so a browser the Pi lists is offered away from home
   too (Dewi away, a PC browser at home).

If Dewi wants it, it is one nginx block, one approval route and one key check, and the order above is the order to do it in.

## Picker follow-ups (ADR 169's open points)

- **A device switched on by hand says so.** `TargetState.switchedOnByHand` is set by the Pi for the speakers named in
  `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND` (`TargetLink.switchedOnByHand`), and such a speaker is now reported `ASLEEP` while its
  Bluetooth sink is not there (`BluezLink.look`, refreshed on the deck's 10 s beat; a speaker the Pi links itself is never
  claimed to be off; before the first look nothing is claimed). `PlayOnOption.hint` is one of nothing, "💤 Off · picking it
  switches it on" (a TV with a wake) or "🔌 Off · switch it on by hand"; picking such a device sends no wake, and the remote
  queue's header says "🔌 Soundmic is off · switch it on by hand" instead of offering "tap to wake". **Caveat:** "off" is "no
  Bluetooth sink", so a by-hand speaker that is on but has not linked to the Pi reads as off until it does.
- **One colour for a device everywhere.** All eight places that read a device's colour (picker dot and row chip, item-menu dot,
  queue chip, remote queue, row badge, mini and full player badge) now ask `TargetColours.of(colours, id)` and convert with one
  `asColour()`; none of them disagreed before, but each wrote its own fallback and one used a different conversion. One
  real gap was found and fixed: a device first met in the answer to a queue request had no colour until the next read, so
  it showed in the phone's tangerine in between (`replace` now paints).

## Consequences

- A browser plays queues the Pi holds, with the phone off, and the phone's other targets are unchanged. The PC screen the phone
  serves (ADR 81 and 93, with Watch here) is unchanged and stays the way to watch a phone's own queue on a PC.
- The Pi's cast port now answers a page and `/screen*` as well as per-offer paths (the server README said otherwise and is
  corrected). Nothing needs changing in the compose file or nginx for the home network; `TOTUM_CAST_BASE` is required (without
  it no browser is listed, and the app is given no address).
- `ChromecastPlayer` falls back to giving a raw URL when the Pi cannot build a manifest; a browser cannot play that (its page loads
  from its own origin), so a stream the Pi cannot build for a browser stalls and the deck says it has stopped moving.
- Not run on a real Fire Stick, Silk or the real Pi from here. What was run: a locally built `totum serve` (the very
  build in `cli/build/install`, `TOTUM_CAST_BASE=http://127.0.0.1:8771`) and a headless Edge on Windows opening
  `http://localhost:8771/`. The page showed the Pi's wording (no Watch here, no Switch phone, "In Totum, choose ▶️ Play on, then
  “Edge”."); `GET /targets` listed `browser-…` "Edge" `BROWSER` `ON` and carried `browserAddress`; a generated 90 s test card (H.264
  and AAC, served by a Range-capable stub on loopback) queued on it played at 640x360 with its clock advancing; pause, play, a
  seek to 60 s and a volume of 40 (the page's `video.volume` read 0.4) each went through and the Pi's state agreed; stop put the
  waiting screen back after 1.5 s. Not heard: sound (headless), full screen, the click-for-sound overlay with a real remote, Silk.
- Tests: `ScreenOnThePiTest` (core:cast), `BrowserControlTest`, `BrowserSourceTest`, `BrowserServingTest`, `BluezTest`,
  `TargetDeckSpeakerTest` (cli), `PlayOnTest`, `TargetStateWireTest`, `TargetRulesTest` (core:data), `TargetsRepositoryTest`,
  `DeviceMarksTest` (app), instrumented `PlayOnPickerTest` and `DeviceColourSurfacesTest` (run on totum-api35 at 320x640 @ 160 dpi and at 1080x2400 @ 420 dpi).
