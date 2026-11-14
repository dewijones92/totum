---
title: A browser is a target the Pi plays to, found by opening the Pi's address; Play on… says how a device comes on
kind: adr
status: accepted
updated: 2026-11-14
---

# 170. A browser is a target the Pi plays to, found by opening the Pi's address

- Status: Accepted
- Date: 2026-11-13
- Amends: [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md) (the open points: how a device that is
  switched on by hand is worded, and one rule for a device's colour), [ADR 81](0081-the-pc-screen-is-a-page-the-phone-serves.md)
  (the receiver page now lives in `:core:cast` and has a second host)
- Amended 2026-11-14 by the end-of-batch review: a goodbye on page close and its own wording, the page's volume as the sleep
  fade's base, one live tab per browser, unguessable ids and eviction, no key in a log or a browser, and a browser is not offered
  away from home (the sections below say which sentence changed)
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
  command was added for the Pi's sleep fade and slider (a browser's volume is `video.volume`, never set by a play, as for
  every device). **Amended:** the page now reports its own `video.volume` (`vol=` on its once-a-second state call, 0 to 100),
  and `BrowserControl.volumePercent` follows it, except for 2.5 s after the Pi itself set a level (a report that left before the
  page applied the command would put the old level back). Before this the Pi only knew a level the slider had set, so
  `DeckSleep` had no base for a browser and the sleep timer never faded one ("the Pi's sleep fade" above was not true); now
  the base is whatever the page is at, and a page nobody touched reads 100.
- **The short address.** `TOTUM_CAST_BASE` without its scheme (`192.168.0.5:8771`), sent to the phone as
  `TargetsAnswer.browserAddress` (`browserAddressOf`). The phone shows it under **🖥️ Add a browser…** at the foot of the
  picker row and of every item's "Play on…" list, which opens a dialog with the address and 📋 Copy address, only at home (ADR 169's rule) and even when no other device
  is listed. Typing `192.168.0.5:8771` into Silk with a TV remote is the whole set-up; there is no key and no pairing.
- **No Jellyfin key in a log or a browser (amended).** `ScreenLink.send` logged every command whole, and a `load` carries the
  stream address. A key could reach a browser: for a Jellyfin item the Pi's own address is `…/Videos/<id>/stream?static=true&api_key=…`
  (`TargetStreams`), and when `ChromecastPlayer` could not build a manifest it sent that address to the page as it came. Now the
  log keeps an address only up to its question mark (`…/stream?…`), and a control that `keepsAddressesPrivate` (a browser, not a TV on
  the home network) is refused such an address: the item fails with "no stream could be built for <name>, and the item's own
  address carries a key", which the deck words as "couldn't play". The key never reaches a manifest, which only names the Pi's own
  per-offer paths. The Pi's proxy for Jellyfin streams ([ADR 154](0154-jellyfin-is-browsed-and-streamed-through-the-pi.md)) is on
  the main listener behind the device token (`JellyfinRoutes`, [ADR 90](0090-a-token-per-device-and-none-in-links.md)), not on the cast port, so a browser
  has no tokenless way to it; giving browsers a proxied Jellyfin stream is a separate piece of work, not done here.
- **No key on the Pi's page: the home network is the gate**, as it already is for the TV streams on that port (per-offer random
  paths, no token, because a TV cannot send one). [ADR 90](0090-a-token-per-device-and-none-in-links.md) is kept: no
  home-server token is in any link, and the page never sees one. Anyone on the home LAN can open the page and list a
  browser in the picker, and nothing plays there unless a person picks it. **This is the sentence the remote case below
  breaks, and why it is not built.**
- **Names and ids.** A browser's target id is made from its page id, which the page keeps in `localStorage`, so the same
  browser is the same target (and keeps its queue, `target-<id>.json`) next time. **One live tab per browser (amended):** the page
  keeps its id in `localStorage`, which every tab of one browser shares, so two tabs said hello as one target and both played.
  Each page load now has its own `tab` value (sent on every call); the newest tab to say hello wins, `ScreenLink` answers the
  older one `409 {"replaced":true}` on its next poll, state, seek or Watch call (and wakes an older tab that is waiting on a long
  poll at once), and the older page pauses, unloads and says "This browser was opened in another tab, so this tab has stopped".
  A page that sends no tab (an older one) is not checked. The id, and so the target and its queue, stay the same across tabs and
  reloads. **Ids the server makes** (a hello with no id) were `screen-<n>-<time>`, guessable; they are now 128 random bits
  (`SecureRandom`). `/screen/seek` answers an id nobody has exactly as it answers a real one (200, nothing seeks), so it no
  longer says whether an id exists; a poll from an id the server has forgotten is told to say hello again.
  Its label is the browser's name from
  its user agent (Silk, Edge, Chrome, …); two browsers with one name are told apart by the end of their ids. `TargetDeck.relabel` lets a renamed device change
  its label without losing its deck; `TargetDecks.sourcesChanged` makes a new browser appear at once rather than at the next
  once-a-minute look. Nothing in the page lets a person type a name yet (a stored `totumScreenName` is read, nothing writes it).
- **A browser that goes quiet is a lost connection.** It is "reachable" only if heard in the last 10 s; after 30 s of silence
  while it has a deck, `BrowserScreens.sweep` tells the deck (`lost`), which stops, keeps the place and says so, as for a TV.
  **Amended:** the page also says goodbye when it is closed (`navigator.sendBeacon` to `/screen/bye` on `pagehide`; a replaced tab's
  goodbye is ignored, so closing the old tab never stops the new one), the screen is unheard at once (it stays listed, as a lost one does, so the deck's message can be read; a play to it is
  refused as "couldn't reach"), and the deck is told "the browser page was closed" after a 1.5 s grace, which a reload's hello
  cancels (the sweep now runs every second). A page that
  comes back from the back/forward cache re-says hello, and a poll from a screen that said goodbye is told to say hello, so a
  false goodbye heals itself. The 30 s of silence stays as the backstop (a crashed tab or a lost network sends nothing). Either way
  the deck says **"🖥️ <name> was closed"** (was "📺 … was switched off", worded for a TV): the trouble kind is still `SWITCHED_OFF`
  on the wire, so no phone meets an enum value it does not know, and the words are chosen by the target's kind on the Pi
  (`TroubleWords`) and on the phone (`TargetWords`, `target_trouble_closed`). A browser unheard for 10 minutes is forgotten with
  its commands, its listener and its in-memory deck (`ScreenLink.evictGone`, `BrowserScreens.onEvicted`, `TargetDecks.forget`);
  its saved queue file stays on disk, so the same browser coming back has its queue (and the files of browsers that never come
  back stay too; nothing here deletes them).
  The picker may still list a closed browser for up to a minute (the target list is re-read once a minute; arrivals are immediate).

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
4. **The picker must be told** (amended): `TargetKind.BROWSER` was `reachableAway`, which offered a listed browser away from
   home against ADR 169's "away, only this phone". It is not any more; when remote browsers are built, set it again and say so in
   ADR 169.

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
- Tests: `ScreenOnThePiTest`, `ScreenLinkTest` (core:cast: tabs, goodbye, ids, eviction, log redaction, uniform seek reply),
  `BrowserControlTest` (the page's volume, goodbye and its grace, eviction), `ChromecastPlayerTest` (no keyed address to a browser),
  `TargetDeckStartTest` ("was closed"), `TargetDecksTest` (`forget`), `TargetRoutesSlowStartTest` (an old phone), `BrowserSourceTest`,
  `BrowserServingTest`, `BluezTest`,
  `TargetDeckSpeakerTest` (cli), `PlayOnTest`, `TargetStateWireTest`, `TargetRulesTest` (core:data), `TargetsRepositoryTest`,
  `DeviceMarksTest` (app), instrumented `PlayOnPickerTest` and `DeviceColourSurfacesTest` (run on totum-api35 at 320x640 @ 160 dpi and at 1080x2400 @ 420 dpi).
