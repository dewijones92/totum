---
title: 📺 Cast to Apple TV (AirPlay) and DLNA players too
kind: todo
status: built 2026-11-14 (ADR 174), sound only; real-device tests owed
priority: medium
updated: 2026-11-14
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

## Built 2026-11-14 ([ADR 174](../adr/0174-dlna-and-airplay-are-more-controls-behind-the-one-cast-player.md))

Decided with Dewi's "same experience as the Chromecast … DRY … ONE BRAIN": DLNA (`DlnaControl`, UPnP SOAP) and AirPlay
(`airplay_helper.py`, pyatv) are two more `CastControl`s behind the one `ChromecastPlayer`; the one stream server gained
one route, `hls.m3u8`. Devices are real: the LG OLED65C24LA speaks AirPlay 2 (pairing mandatory), and Kodi on the Fire
Stick has a DLNA renderer.

Owed, in order:

1. **Real-device runs**: pair the LG (`totum airplay-pair "LG TV"`), play, pause, seek, stop; Kodi over DLNA. The LG may
   refuse audio-only HLS (`supportsUIForAudioOnlyContent` is false).
2. **The picture** over HLS (a byte-range video playlist from the layouts DASH already uses); today a picture item plays its
   sound and the notice says so.
3. **AirPlay inside `bin_private`**: if the LG stalls at start, the sender address given for AirPlay 2 timing is the
   container's; host networking or a published timing port is the fix.
4. **A Kodi JSON-RPC control** if DLNA proves flaky on the Fire Stick (one more `CastControl`, credentials from a file).
5. A volume for AirPlay (URL playback has none; RAOP has).
