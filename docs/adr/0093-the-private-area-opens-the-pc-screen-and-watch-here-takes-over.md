---
title: The private area opens the PC screen, and Watch here takes over
kind: adr
status: accepted
updated: 2026-10-29
---

# 93. The private area opens the PC screen, and Watch here takes over

- Status: Accepted
- Date: 2026-10-29

## Context

Dewi, 2026-10-29: *"the private area screen on my Raspberry Pi … I want an extra one … Dewi's Totum, I click on that
and then I can view … watch the current item playing … a service on the phone that does this if it's plugged in on
Wi-Fi."* ADR 81 already served the page from the phone and redirected the Google-gated `crashlog…/screen` to it, but
the PC still had to be picked on the phone (🖥️), and the page said only "Waiting for Totum".

His answers: one **▶️ Watch here** click on the page (Edge needs a click for sound anyway); ready **while the phone is
plugged in and on Wi-Fi**; with nothing playing, the **queue's current item**; a **"📱 Dewi's Totum"** tile on the
private area.

## Decision

- **The page knows what is on the phone.** `ScreenLink.nowPlaying` (title, by, playing, which screen) is set by
  `PcScreens` from the session's player on every player event and every hand-over; `/screen/hello` and the
  once-a-second `/screen/state` reply carry it (`state` now answers 200 with JSON, not 204). The waiting page shows
  "▶️ On your phone: …" or "📭 Nothing playing on the phone", and the button while this screen is not the one playing.
- **Watch here** calls `/screen/watch?key&id` (a screen that has said hello; others are refused). `PcScreens.watch`
  runs on the main thread: it switches the session to this screen exactly as the 🖥️ menu does, then plays. If the
  phone has nothing loaded it broadcasts `SCREEN_WANTS_AN_ITEM`; `Media3PlaybackController.screenWantsAnItem` carries
  it to the app, and `PlaybackQueue.playCurrentOrNext` plays the current entry (or the next after where the queue left
  off), which lands on the screen because the session already points there. The click also unlocks sound and full
  screen.
- **Ready while charging on Wi-Fi.** The page server lives as long as the app's process (the app holds its media
  controller for its whole life, and the always-pinned alarm board keeps the process up), and charging keeps Android
  out of Doze. `ScreenReadiness` (power connected/disconnected, any Wi-Fi network) posts a minimum-importance
  "🖥️ Ready for your PC" notice while both hold and clears it otherwise. It does not start anything: the server is
  up because the process is. No new foreground service.
- **The tile** is one card on `privatearea.333133333.xyz` (dot-files on the Pi) linking to the existing
  `https://crashlog.333133333.xyz/screen` redirect.

## Amended 2026-10-29 (batch review)

- Nothing the queue can play (an empty queue, an item refused) hands straight back to the phone
  (`PlaybackQueue.playForAScreen`), and the page says "⚠️ Nothing started here: is the queue on your phone empty?"
  after 20 s; until then the button stays disabled, so a second press cannot fetch the same item twice.
- Watch here on a second screen while another plays stops the first and moves the item and its place to the second
  (it used to leave the first playing with no remote and send the second only "play").
- The request for an item goes only to Totum's own controllers (as the queue-skip buttons do), not a broadcast to
  System UI and Bluetooth; the TV player is watched too, so the page's title follows a cast.

## Amended 2026-10-29 (from Dewi's report on 0.1.749): the Pi kept an old address

Dewi's bookmark opened an old "Waiting for Totum" page: the Pi held `…138`, the phone served at `…85`, and the phone
had logged "no PC screen page to tell the Pi about". The address reaches the app in the media session's extras, and
Media3 reports only *changes*; the address set while the service started, before the app's controller connected, was
never seen. The controller now applies the session's starting extras on connect, and whenever the phone becomes ready
on Wi-Fi (charging) the app asks the service to re-read its address (`refreshScreenPage`), so a new address is
announced. The cast status line now names the page address.

## Consequences

- The flow is: private area → 📱 Dewi's Totum → ▶️ Watch here; the phone becomes the remote, as with the TV.
- Reports carry `screen.ready`, `screen.charging`, `screen.wifi`; the Watch press, its choice (move or ask the queue)
  and the queue's answer are logged under `[screen]` and `[queue]`.
- If the process is ever killed while charging, nothing answers until Totum starts again; the board's foreground
  service makes that rare.
- Tests: `ScreenLinkTest` (watch, refused stranger, now-playing in state and hello), `ScreenReadinessTest`,
  `PlayCurrentForAScreenTest` (current, next, and the empty queue handing back); the two-screen hand-over was checked
  with two headless Edge pages, not by a test; end to end with `WATCH=1 tools/cast-bench/screen/screen.sh` (headless Edge presses the
  button). Verified 2026-10-29 on totum-api35: nothing loaded → the queue's current item (Threads, from 1:09:59) played
  in Edge at 960×720 with English captions; with something loaded the page named it and Watch here carried on from the
  phone's place; unplugging cleared the notice and plugging in brought it back.
