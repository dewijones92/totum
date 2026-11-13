---
title: 📺 Cast to Apple TV (AirPlay) and DLNA players too
kind: todo
status: backlog (to discuss), 2026-11-13
priority: medium
updated: 2026-11-13
---

# 📺 Cast to Apple TV (AirPlay) and DLNA players too

Dewi, 2026-11-13: *"It'd be great if we could cast to things like Apple TV, DLNA as well … the same experience as
us, like a broadcast … shove that on the backlog. We'll discuss it later."*

## What it means

More kinds of target beside Chromecast, Bluetooth speakers and the browser page, with the same experience: they
show in the "Play on…" sheet, get their own queue and colour, play Totum's processed stream (skip silence, boost,
the casting brain of ADR 156), and are driven from the phone or the Pi.

## Starting points (not yet investigated)

- **DLNA / UPnP renderers** (many smart TVs, AV receivers, Kodi): UPnP AVTransport (`SetAVTransportURI`, `Play`,
  `Pause`, `Seek`, `GetPositionInfo`). Discovery is SSDP multicast, which does not cross the Pi's `bin_private`
  bridge or WSL; the Pi's SSDP scan notes apply (its firewall drops the replies). A renderer can usually fetch the
  same HTTP stream the Cast receiver gets (`/c/<token>/…`), though many want a progressive MP4 or MP3 rather than DASH.
- **Apple TV (AirPlay 2)**: no public SDK on Android; open implementations exist (pyatv on the Pi can stream a URL
  and control playback). Needs pairing and a credential kept on the Pi.
- Both fit the target model of ADR 152 (`TargetKind`, a `TargetPlayer` per kind) and the one casting brain of ADR
  156; neither should grow a second stream server.

## To discuss with Dewi

Which devices he actually has (an Apple TV? which DLNA renderers?), whether the phone or the Pi should drive them,
and how much of Totum's processed sound matters on them.
