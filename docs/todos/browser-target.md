---
title: 🖥️ A browser is a Play on… target (open the Pi's address in any browser)
kind: todo
status: built 2026-11-13, Batch B P9 (home network; away from home decided and not built; see the ADR)
batch: B (2026-11), P9
priority: high
updated: 2026-11-14
---

# 🖥️ A browser is a Play on… target

Dewi, 2026-11-13: a link he can open in any browser (Silk on a friend's Fire Stick, say) that turns that browser into a
receiver, on the existing PC screen page, with a short address that is easy to type on a TV remote; it appears in the "Play on…"
picker once a browser has opened it and gets a colour like every target.

Decision and design: [ADR 170](../adr/0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md). Feature doc:
[pi-plays-everywhere](../features/pi-plays-everywhere.md).

## Reuse (searched before building)

- **The page and its protocol:** `screen.html` and `ScreenLink` (ADR 81 and 93), moved to `:core:cast` so the Pi serves the very bytes the phone does; nothing was
  copied.
- **The player:** `ChromecastPlayer` and `CastLink` drive a browser through a new `CastControl` (`BrowserControl`), so the manifest, the
  relayed picture, the processed sound, the cut timeline and the cut-end nudge are the TV's, not a second implementation (ADR 156).
- **The target model:** `TargetSource`, `TargetDeck`, colours, chips, marks and the picker are unchanged; a browser is only a new source.
- **The stream server:** the Pi's existing cast server (port 8771); no second one.

## Done

- [x] The Pi serves the page at the bare address of its cast port; `GET /targets` carries `browserAddress`.
- [x] A browser that opens it is listed as a `BROWSER` target (colour, queue, badges, picker, remote controls).
- [x] The phone shows the address under 🖥️ Add a browser… (picker row and item menu), at home, with 📋 Copy address.
- [x] A device switched on by hand says so, and one rule for a device's colour (ADR 169's follow-ups).

## Away from home: decided, not built

A browser on another network cannot reach the Pi's LAN address, so it has to be on the same network as the Pi. What the remote case
needs, in the order to do it (full text in the ADR): an nginx server block and certificate for a public name in front of
`127.0.0.1:8771` (dot-files); pairing (a 4-digit code on the page, approved from the phone with its device token, a per-browser key
the Pi can recompute) so that only an approved browser is a target and can read streams; an uplink decision (a remote browser pulls
5 to 10 Mbit/s of 1080p through the home upload). The page already works from any origin. Not built: it needs a change outside
this repo that cannot be tested from here, and a security-sensitive approval flow.

## Seen on this laptop (2026-11-13)

A locally built `totum serve` and a headless Edge on Windows: the page at the bare address, the browser listed as a target, a generated
test card playing on it with pause, play, seek and volume going through, stop returning the waiting screen (details in the ADR).
The phone-hosted page, with a stub standing in for the phone, still shows "On your phone", Watch here and Switch phone.

## Fixed after the end-of-batch review (2026-11-14, ADR 170 and 158 amended)

A new Pi no longer makes an old phone (v0.1.911 and earlier) play twice; a closed browser page is noticed at once (goodbye beacon) and
worded "was closed"; a browser's sleep timer has a volume to fade from (the page reports its own); two tabs of one browser no longer
both play (newest wins); ids are unguessable, a seek does not say whether an id exists, silent browsers are forgotten; no key in a log
or in a browser's hands; a browser is not offered away from home; the picker says "🏠 Home devices unreachable" instead of falling back
silently.

## Owed (real devices)

- Silk on a real Fire Stick (and its remote's click for sound), a laptop browser on the real Pi's address, the browser page while the phone is off.
- The picker row and item menu entry on a real phone; the by-hand hint with the real Soundmic and `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND`.
- The goodbye beacon and "closed" wording on a real Silk (Fire Stick) and a phone's browser, where `pagehide` can behave differently
  from Chromium's; two tabs on a real desktop browser; sleep-timer fade audible on a real browser.
- Not built: a name typed into the page (a stored name is read, none is written).
