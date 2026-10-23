---
title: The PC screen is a page the phone serves, found through a Pi bookmark
kind: adr
status: accepted
updated: 2026-10-23
---

# 81. The PC screen is a page the phone serves, found through a Pi bookmark

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-23: *"we can cast our TV … I want to be able to … watch from my phone on my PC display"* (Edge on
Windows). He asked whether a cast receiver on the Pi should sit in the middle; the answer was that the phone should
keep doing what it does for the TV (ADR 68: relayed video packets, Totum's processed audio, so skip-silence, boost,
SponsorBlock and the queue all carry on) and the Pi should only **find the phone**. A page on the Pi itself cannot play
the stream: it is https and the phone serves plain http on the home network, so the browser blocks it as mixed
content. He chose "the Pi bookmark finds the phone".

## Decision

- **The page lives on the phone.** `CastStreams`' server (port 8765, the one the TV fetches from) also answers
  `/screen` (the receiver page, `core/playback/src/main/assets/screen.html`, Shaka 4.11.0) and `/screen/hello`,
  `/poll`, `/state` (`ScreenLink`). Every call but the page itself needs the phone's screen key (128 random bits,
  kept in prefs `totum_screen`, carried in the page's `#key=` fragment so it never reaches a server log).
- **The page is the remote's ear, not a cast device.** It says hello with its name, the codecs Edge can play
  (`MediaSource.isTypeSupported` probes for AVC, VP9, HEVC, AV1) and its height, then long-polls (20 s) for commands
  (`load`, `play`, `pause`, `seek`, `rate`, `subs`, `stop`) and reports its position every second. It keeps its id in
  `localStorage`, and after a network blip says hello again **with the same id** (the first version took a new one,
  so the phone kept talking to an id nobody listened on).
- **On the phone it is one more player.** `ScreenPlayer` (a `SimpleBasePlayer`) turns the session's commands into the
  page's and its reports back into state, wrapped in `TimeMappedCastPlayer` like the TV's, and `PcScreens` switches the
  session between it and the phone's player exactly as Cast does (`switchTo`). The manifest it hands over is the TV's
  (`CastStreams.urlFor`); the page loads it from its own origin, so the formats follow the screen's codecs
  (`screenProfile`). Captions follow the phone as they do on a TV: the session's track-selection parameters
  (`CaptionPreference`, ADR 78) reach `ScreenPlayer`, which sends the language (or off) with every load and on every
  change, and the page selects that text track of the stream.
- **The UI:** a 🖥️ button beside Cast in the player's top bar and on the "📺 Casting to …" card lists the screens
  that have said hello in the last minute, and "Back to the phone".
- **The Pi bookmark:** the phone posts `{"kind":"screen","url":…}` to the crashlog server's open `/ingest` whenever its
  page address changes (`ScreenAnnouncer`; never from an emulator or a test run, whose address would replace the
  phone's). The server keeps the latest only if it is a home or WireGuard address (10/8, 172.16/12, 192.168/16,
  100.64/10), and `https://crashlog.333133333.xyz/screen` (Google-gated like the rest of that host) redirects to it.
  No nginx change.

## Consequences

- The phone does the work for the PC as it does for a TV: its Wi-Fi and battery carry the stream, and it must stay on
  the same network (or WireGuard) as the PC.
- Edge needs one click on the page before it may play sound (the page asks for it).
- Anyone on the home network who reads the key from the bookmark could drive the page; the key is per phone and can be
  reset by clearing `totum_screen`.
- A forged `/ingest` could point the bookmark at another home-network address, which is why only private ranges are
  kept.
- Tests: `ScreenLinkTest` (key, hello, long poll, reports), `ScreenCaptionsTest`, `ScreenAnnouncerTest`, `tests/test_screen.py` on the
  server; `tools/cast-bench/screen/screen.sh` drives it end to end (the emulator serves, a headless Edge on Windows
  plays through `adb forward`). Verified 2026-10-23: 1280×720 in Edge, pause and play from the phone, back to the
  phone.
