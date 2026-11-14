---
title: DLNA and AirPlay devices are two more controls behind the one cast player, fed one more route of the one stream server
kind: adr
status: accepted
updated: 2026-11-14
---

# 174. DLNA and AirPlay are more controls behind the one cast player

- Status: Accepted
- Date: 2026-11-14
- Builds on: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (targets),
  [ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md) (one casting brain),
  [ADR 170](0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md) (a browser as a `CastControl`),
  [ADR 166](0166-the-sleep-timer-fades-by-how-loud-it-sounds-on-the-phone-and-on-a-target.md) (the fade)
- Backlog item: [cast-to-airplay-and-dlna](../todos/cast-to-airplay-and-dlna.md)

## Context

Dewi, 2026-11-14: *"the DLNA and AirPlay I want similar experience to the Chromecast if possible … DRY"*, and, before bed,
*"please try to keep DRY and ONE BRAIN"*. Two real devices turned up while this was built, both probed read-only from
the laptop:

- **The LG OLED65C24LA** (already `LG TV` in `TOTUM_CAST_TARGETS`) is an AirPlay 2 receiver on port 7000. `GET /info`
  answers a plist (`AirPlay;3.5.0.244`, webOS, `playbackCapabilities.supportsUIForAudioOnlyContent = False`), and pyatv's
  unicast scan reports the service with `PairingRequirement.Mandatory`. Its DLNA is unconfirmed (no SSDP reply).
- **Kodi 21.3 on the Fire Stick** runs Kodi's UPnP renderer (`MediaRenderer:1`, AVTransport, RenderingControl,
  ConnectionManager; SCPD lists `SetAVTransportURI`, `Play`, `Pause`, `Seek`, `Stop`, `GetPositionInfo`,
  `GetTransportInfo`, `SetVolume`, `GetVolume`). Its port (1285 today) can change when Kodi restarts.

## Decision

- **One brain, one player, one deck.** Nothing new in `TargetDeck`, `ChromecastPlayer`, `CastLink` or the stream server's
  session logic. A device is a `CastControl` (ADR 170's seam: load, pause, seek, volume, stop, release, a reading, an end
  told once, a loss told once, reachable). Two more implementations:
  - `DlnaControl` (Kotlin, `:cli`): UPnP AVTransport and RenderingControl by plain SOAP over HTTP, with a small
    description/SOAP/DIDL layer (`Upnp.kt`). No helper process: UPnP needs none. It reads the renderer's own position by
    polling `GetPositionInfo`/`GetTransportInfo` once a second while an item is loaded, ends the item when the renderer
    goes `STOPPED` after having played (failed on `ERROR_OCCURRED` or when it never started within 20 s), loses the
    renderer after three missed polls, seeks to a start place once the renderer is playing, and reads the renderer's
    volume at connect and every ten polls.
  - AirPlay is the **same `PyCastControl` as Chromecast**, pointed at `airplay_helper.py` (pyatv 0.18.0) instead of
    `chromecast_helper.py`. The helper speaks the one JSON-lines protocol (`CastWire`), so the Kotlin side gained a
    `helper`, a `format` and a name for the kind and nothing else. The helper starts `play_url` as a task, keeps the
    connection pyatv opens, and drives AirPlay's own HTTP endpoints on it: `POST /rate?value=0|1` (pause, resume),
    `POST /scrub?position=` (seek), `GET /playback-info` (position, length, rate).
- **One new output format, as one route of the one stream server.** DASH is what Chromecast and the browser page read;
  neither AirPlay nor most DLNA renderers play it. `CastStreamServer.urlFor(item, format)` now takes a `CastFormat`:
  `DASH` (unchanged, the default) or `HLS`, served at `/c/<token>/hls.m3u8`. The playlist is a VOD fMP4 playlist
  (`#EXT-X-MAP` plus one `#EXTINF` per segment, closed by `#EXT-X-ENDLIST`) over the **very same** `audio/init.mp4` and
  `audio/<n>.m4s` the DASH manifest names: no new media code, the same `ProcessedAudio`, so skip silence, boost, the
  timeline map for a cut item and the cut-end nudge all apply unchanged. A progressive MP3/ADTS route was rejected: the
  engine produces segments, and a progressive stream would need a second producer path. Each control states its format
  (`CastControl.format`, DASH by default) and `ChromecastPlayer` asks for that one, so there is still one player.
- **Sound only, for now, and said so.** The playlist carries the processed sound. The picture of a video (the original
  relayed by range, a DASH-specific layout) is not carried: an item with a picture on a DLNA or AirPlay device plays its
  sound and the target's notice says `🔈 <name> plays the sound of this over HLS; there is no picture yet`. A byte-range
  video playlist is the follow-up. Live manifests are not offered as HLS (the player falls back to the address as it
  is). A playlist needs a known length (it is a VOD list); an item with none is refused rather than served as an endless one.
- **No new `TargetKind`.** A DLNA or AirPlay device is a `TV` on the wire (a Chromecast keeps `CHROMECAST`). Reason, with a
  test: the phone decodes `/targets` with an enum that throws on a kind it does not know, and one throw loses the whole
  list, so an old phone would lose every target the moment a `DLNA` device was configured (ADR 158's compatibility rule).
  The protocol is the Pi's own business: it is in the configuration, the logs (`cast-target`) and the notice.
- **One device that speaks several protocols is one target.** The same name in `TOTUM_CAST_TARGETS`,
  `TOTUM_AIRPLAY_TARGETS` and `TOTUM_DLNA_TARGETS` is **one** target (`lg-tv`) with one queue, one colour, one volume and
  one wake, in the picker exactly as before. Two entries (`LG TV` and `LG TV (AirPlay)`) were rejected: two decks would
  each think they own one screen, play the same queue twice and fight over the one volume. `FirstReachableControl` is a
  `CastControl` over the device's controls: `connect` tries the protocol that last answered, then the preferred order
  (Chromecast, AirPlay, DLNA, or `TOTUM_CAST_PROTOCOL_FIRST=LG TV=airplay` per device), uses the first that answers, tells
  the player its format, and stops what the old protocol held when it switches. The wake (`@mac`) is protocol-independent
  and probes every configured protocol's port.
- **Configuration, not discovery** (ADR 152's rule: multicast does not cross `bin_private`, and the Pi's firewall drops
  SSDP replies). `TOTUM_DLNA_TARGETS=Kodi=http://192.168.0.211:1285/;…` (the device description address, as `name=url`) and
  `TOTUM_AIRPLAY_TARGETS=LG TV=192.168.0.219;…`, parsed by the same `knownCastsFrom` as `TOTUM_CAST_TARGETS` (an optional
  `@mac` works on either). `tools/cast-bench/discover-dlna.py [host]` prints `name=url` lines to paste for DLNA (SSDP, run
  on the Pi host, optionally unicast to one host: it found Kodi's current port from the laptop); for AirPlay,
  `pyatv`'s own `atvremote --scan-hosts <ip> scan` in the container does it.
- **AirPlay needs pairing, as a step you drive**: `totum airplay-pair "LG TV" [--pin=1234]` (in the container,
  `docker exec -it totum-fetch /opt/totum/bin/totum airplay-pair "LG TV"`). The name must be in
  `TOTUM_AIRPLAY_TARGETS`. The helper's `pair` mode starts pyatv's pairing (the TV shows a PIN), asks for it (or takes
  `--pin`), and writes the credentials to `<TOTUM_DATA>/airplay/airplay-<id>.json` (`/var/lib/totum-fetch/home/.totum/airplay/`
  on the Pi) with mode 600; they are never printed, logged or put in a link. A play on an unpaired receiver reports
  `AirPlay needs pairing first: run totum airplay-pair <host>`.
- **Volume rule, unchanged and tested.** A load never sets a volume. Only the slider and the sleep fade write one, through
  `CastControl.volume`: DLNA writes the renderer's master volume and reads it back; the AirPlay helper **declines**
  (`note`: URL playback has no volume of its own, the TV's volume is the TV's), so `volumePercent` is null and the fade has
  nothing to move on an AirPlay device (the sleep timer still pauses at its end). The DLNA control never raises a device
  above the level it was at.
- **No address that carries a key is ever handed to these devices.** `CastControl.keepsAddressesPrivate` (ADR 170's
  rule for browsers) is true for `DlnaControl` and for the AirPlay control, so when no stream could be built and the item's
  own address carries `api_key=` (a Jellyfin stream), the item fails with a reason rather than the device being given the
  key; a merged device answers for the protocol in use. The playlist address the Pi hands them is its own random
  per-offer path and carries no secret.
- **No new `TroubleKind` and no new wire value** for these devices' wording: their notices are plain text in the target's
  `problem` (an unknown enum value would break an old phone's decoding, ADR 158).
- **Work does not grow with the queue** (Dewi's real queues hold thousands of items). A control receives one item at a time
  from the deck; `DlnaControl` makes exactly three actions per item (`Stop`, `SetAVTransportURI`, `Play`) and reads nothing
  ahead (`SetNextAVTransportURI` is not used), and the stream server keeps at most six sessions. A test sends 300 items and
  counts the actions.
- **Kodi.** It plays through `DlnaControl` today (`TOTUM_DLNA_TARGETS=Kodi=http://192.168.0.211:1285/`). A Kodi JSON-RPC
  control (authenticated, richer) is **not built**: it would be one more `CastControl` behind the same player with its
  credentials read from a file at call time, never from the configuration or a log, and it can be added without touching
  anything above. Say if DLNA proves unreliable on the Fire Stick.

## Consequences

- Old phones and old Pis keep working together: no wire field changed. An old Pi lists no such device; an old phone shows
  a DLNA or AirPlay device as a TV.
- `ChromecastSource` became `CastDeviceSource` (it builds every protocol's control and merges devices by name); its
  `TOTUM_CAST_TARGETS` parsing and the wake are as before. The player keeps its name, `ChromecastPlayer`, to keep the
  history of ADR 170 and the tests it already has readable; it is the one cast player.
- `PyCastControl`'s `handbackFile` is now `stateFile` (the Chromecast hand-back or the AirPlay credentials).
- The Pi image carries `pyatv==0.18.0` and `airplay_helper.py` (`TOTUM_AIRPLAY_HELPER`); the Kotlin arrives with the next
  release. Pinned, because the helper uses pyatv internals (`http_connect`, the connection its `play_url` opens).

## Not proven, and why (read before relying on it)

- **The real LG over AirPlay 2** has not been paired or played. What is proven: pyatv finds it (unicast mDNS from the
  laptop), `/info` is the AirPlay 2 shape, and the helper's whole life (discover, connect, load, position, pause, seek,
  resume, stop, end, volume declined) runs against a **v1 stand-in** (`tools/cast-bench/airplay-standin`), which models the
  AirPlay HTTP endpoints from pyatv's client code, not from a real receiver. Unknown until the LG answers: whether the
  LG accepts `/rate` and `/scrub` on its connection, whether it plays an **audio-only** HLS at all (its
  `supportsUIForAudioOnlyContent` is false, so it may refuse sound without a picture), and whether pyatv's AirPlay 2
  `/play` (which carries a relative `volume: 1.0`, the stream's own gain, not the TV's master) leaves the TV's volume alone.
- **AirPlay from inside Docker's `bin_private` bridge.** AirPlay 2 start-up tells the receiver the sender's address for
  its timing channel; from the bridge that is the container's private address, which the TV cannot reach. URL playback
  may not need it; if the LG stalls at start, the fix is host networking for the container (or publishing the timing port),
  not a change here.
- **Kodi and every real DLNA renderer** are untested: only a software renderer (gmrender-resurrect, below) and a fake in the
  unit tests have been driven. Many real renderers do not advertise or accept HLS; the control sends HLS under the mime the
  renderer's `GetProtocolInfo` lists (any of the five common spellings), else the standard one, and a refusal is the
  failed item with the renderer's reason in the log.
- **Discovery from inside the container** is not attempted (firewall); only configured addresses.

## Evidence from the stand-ins

Run on this laptop on 2026-11-14, nothing audible (fake sinks), against the build in `cli/build/install` (`totum serve`,
`TOTUM_CAST_BASE` the docker bridge address, `tools/cast-bench/targets-e2e.py` driving `/targets` on the loopback listener):

- **DLNA, gmrender-resurrect 0.1 in Docker** (GStreamer, `fakesink`; it advertises `application/x-hls` in its sink, which the
  control picked up). A 120 s tone queued on it started at once; its position then advanced with the renderer's own clock
  (0 → 5.5 s in 8 s); PAUSE held it at 7000 ms and PLAY resumed from 7000 ms; SEEK 40000 moved it to 40020 ms and on; VOLUME 10
  was written and read back; STOP stopped it. The renderer's log shows it fetched `…/c/<token>/hls.m3u8` from the Pi's one stream
  server, and shows **no volume write at all until the explicit VOLUME 10** (only `CurrentVolume` reads).
- **AirPlay, pyatv 0.18 against a v1 stand-in** (`tools/cast-bench/airplay-standin`): the helper found it by unicast mDNS,
  connected, and the same drive worked end to end: the stand-in fetched the playlist, `audio/init.mp4` (an `ftyp` box) and
  the processed AAC segments (about 97 KB of `moof` each) from the one brain, then saw `POST /rate value=0.0`, `value=1.0`,
  `POST /scrub position=40.0` and a stop; the Pi's state moved from 5.4 s (paused, held) to 40.0 s and on. The volume slider
  left it alone and said why.
- **Kodi's description and SCPD** (not driven): `MediaRenderer:1` with the actions `DlnaControl` uses.

## Tests

`HlsPlaylistTest`, `CastStreamServerTest` (HLS), `core:cast`; `UpnpTest`, `DlnaControlTest` (a fake renderer over real HTTP,
including the 300-item and no-volume-on-load tests), `CastDevicesTest` (merging, order, first reachable, kinds the old
phone knows, a kind it does not, pairing command), `ChromecastPlayerTest` (HLS format, sound-only notice),
`CommandParsingTest`, `:cli`; `airplay_helper_test.py` (a fake device: the commands, the stopping that is not a finish,
credentials mode 600 and never printed); `tools/cast-bench/discover_dlna_test.py`.
