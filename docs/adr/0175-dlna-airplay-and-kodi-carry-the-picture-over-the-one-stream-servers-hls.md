---
title: DLNA, AirPlay and Kodi carry the picture over the one stream server's HLS, and Kodi is driven by its own JSON-RPC
kind: adr
status: accepted
updated: 2026-11-28
---

# 175. DLNA, AirPlay and Kodi carry the picture over the one stream server's HLS

- Status: Accepted
- Date: 2026-11-14
- Builds on: [ADR 174](0174-dlna-and-airplay-are-more-controls-behind-the-one-cast-player.md) (the HLS route, DLNA and
  AirPlay controls), [ADR 68](0068-cast-a-stream-the-phone-serves.md) and
  [ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md) (the one casting brain and its codec rules),
  [ADR 170](0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md) (`CastControl`)
- Backlog item: [cast-to-airplay-and-dlna](../todos/cast-to-airplay-and-dlna.md)

## Context

Dewi, 2026-11-14: DLNA and AirPlay should be *"a similar experience to the Chromecast"*, and *"please try to keep DRY and ONE
BRAIN"*. ADR 174 gave them the brain's processed sound over HLS and said plainly that a video played its sound with a notice.
This adds the picture, and a Kodi control of its own (the Fire Stick runs Kodi 21.3 with an authenticated JSON-RPC API).

## Decision

- **No second stream server, no second session.** The `Session` the Chromecast's DASH manifest is built from (its probe,
  its ladder choice, its remux, its relay, its processed sound) is the session HLS is built from. `CastFormat` stopped
  being "sound only" and became what a format can carry: `HLS` carries H.264 and HEVC in an MP4, `DASH` anything.
  `Session.castVideo()` is the one place the picture is chosen (the best ladder rung, the single relayed layout, or the
  remux); the DASH manifest and the HLS playlists both read it, so they cannot disagree about what the device is sent.
  What HLS adds is a new way of listing what DASH already produced:
  - `hls.m3u8` is a **master playlist** when the session has a picture (`#EXT-X-MEDIA TYPE=AUDIO` for the processed sound,
    one `#EXT-X-STREAM-INF` for the picture with its real `CODECS`, `RESOLUTION` and a bandwidth from the stream or the
    DASH rule), and is still the sound-only media playlist ADR 174 shipped when there is no picture.
    Two more routes, `hls-video.m3u8` and `hls-audio.m3u8`, are the renditions; the segments are the routes that already
    exist (`audio/init.mp4`, `audio/<n>.m4s`, `video/init.mp4`, `video/<n>.m4s`, `video` and `video/r<i>` by range).
  - **A picture the brain remuxes** (an MKV, a non-fragmented MP4: `RemuxedVideo`) is a plain fMP4 playlist over
    `video/init.mp4` and `video/<n>.m4s`, durations from the same timeline the DASH `SegmentTimeline` is written from.
  - **A picture the brain relays** (a fragmented MP4 with a `sidx`: a YouTube video rung, a fragmented download) is a
    byte-range playlist: `#EXT-X-MAP` with the init span, one `#EXT-X-BYTERANGE` per `sidx` reference, over the same
    `video` or `video/r0` route DASH reads with its `SegmentBase`. The one new piece of code is `Sidx` (read the index
    box the layout already located; a `sidx` pointing at another `sidx` is refused rather than cut wrongly).
  - **WebM is not carried.** HLS cannot play it. A ladder is ranked for HLS with VP9 taken off the screen
    (`CastFormat.screenFor`), so a YouTube video gets its best H.264 or HEVC rung instead of a VP9 one the device would
    never be given; a WebM that still arrives is refused by name, like a codec beyond the screen is.
- **Format and picture are chosen per device, from what it says.** Each `CastControl` states `format` (DASH or HLS) and
  `showsPictures`:
  - Chromecast: DASH, pictures, as before.
  - AirPlay: HLS; the helper reads the receiver's mDNS `features` and says whether bit 0 (video) is set in its `ready`
    event (`video`, optional on the wire, so an old helper or Pi is unchanged); unreadable features say nothing and mean yes.
  - DLNA: HLS; `GetProtocolInfo`'s sink decides: HLS or any `video/*` listed means the picture is sent; a sink that lists
    sound only means it is not. A sink that cannot be read is no verdict.
  - Kodi: below.
  A device that takes no picture is handed `CastItem.soundOnly()` and the strip says `🔈 <name> plays the sound of this; it
  lists no video format it takes`. A device that does is offered the picture and, when the codec is beyond what it can show,
  the brain's existing refusal says so (`📺 <name> gets sound only: its video is …`). The screen for a device nobody
  has measured is the unknown-device one (H.264 up to 1080p, "assumed"), narrowed again by HLS to H.264, so an HEVC film on
  an unmeasured DLNA renderer is the sound until the device is known. Kodi and the LG are known.
  Nothing here is a new wire value: no new `TargetKind`, no new `TroubleKind`.
- **Kodi is one more thin `CastControl`, over JSON-RPC.** `KodiControl` calls `Player.Open` with the brain's address
  (`options.resume=false`), `Player.PlayPause` (explicit `play`), `Player.Seek` (a time), `Player.Stop`,
  `Player.GetActivePlayers` and `Player.GetProperties` (`time`, `totaltime`, `speed`) once a second while an item is loaded,
  and `Application.SetVolume` **for the volume slider only**; a load writes no volume, and nothing else does.
  - Connecting calls `JSONRPC.Version`, `Addons.GetAddons` and `Application.GetProperties` and plays nothing.
    `inputstream.adaptive` is detected. Kodi's own ffmpeg plays HLS, which is what the stand-in proves; DASH is chosen
    only when the add-on is present **and** `TOTUM_KODI_FORMAT=auto` is set, because a DASH `Player.Open` through the add-on
    is the one thing that could not be tried (below).
  - **The sign-in** is Basic auth from a file of `user=` and `password=` lines (the same file shape as
    `~/.credentials/kodi-firestick.txt`) named by `TOTUM_KODI_CREDENTIALS`, **read at every call**: never held in the
    configuration, the environment, a URL, a log or a test fixture; the credentials class prints neither field; a refused
    sign-in logs that it was refused and where the file is configured, not what was sent.
  - **One polling and ending machine for the two controls that poll.** `DlnaControl` and `KodiControl` both load, wait for
    a renderer that reports its own clock, seek once it plays, end an item once after it played, fail one that never
    started in 20 s, and lose a device after three missed polls. That was written once in `DlnaControl`; it is now
    `RendererWatch`, and `DlnaControl` shrank by what moved. Each control states only how to look (`GetTransportInfo` and
    `GetPositionInfo`, or `Player.GetActivePlayers` and `Player.GetProperties`) and how to seek.
- **If one device is reachable both ways, Kodi is preferred.** `CastProtocol.DEFAULT_ORDER` is Chromecast, AirPlay, Kodi,
  DLNA; the same name in `TOTUM_KODI_TARGETS` and `TOTUM_DLNA_TARGETS` is one device (ADR 174's rule), driven over
  JSON-RPC when it answers and over its UPnP renderer when it does not (`FirstReachableControl`, unchanged).
  `TOTUM_CAST_PROTOCOL_FIRST=Kodi=dlna` reverses it. A host with a port in its address (`192.168.0.211:8080`) keeps its host
  for the wake and gives its port to the wake probe.
- **The Pi configuration** (exact):
  - `/var/lib/totum-fetch/home/.totum/kodi/kodi.txt`, mode 600, owned by the container's user, two lines:
    `user=<Kodi's web interface user>` and `password=<its password>`. (The container already mounts
    `/var/lib/totum-fetch/home` as `/home/totum`, so no compose change.)
  - in `/var/lib/totum-fetch/fetch.env`:
    `TOTUM_KODI_TARGETS=Kodi=192.168.0.211:8080` and `TOTUM_KODI_CREDENTIALS=/home/totum/.totum/kodi/kodi.txt`;
    keep `TOTUM_DLNA_TARGETS=Kodi=http://192.168.0.211:1285/` for the fallback (its port changes when Kodi restarts: re-run
    `tools/cast-bench/discover-dlna.py 192.168.0.211`).
  - Kodi: Settings, Services, Control, "Allow remote control via HTTP" on, with a username and password; the port is the one
    in the address.

## Consequences

- One change in the brain served three kinds of device: a ladder, a remux or a relay now lists as HLS as well as DASH, and the
  Chromecast's path is byte for byte what it was (`CastStreamServerVideoTest` and the DASH tests are untouched and green).
- A device that needs a progressive file (many real DLNA renderers list `video/mp4` and no HLS) is not served: a progressive MP4
  would need Totum's processed sound muxed into one stream, a second producer path, which ADR 174 rejected and this does not
  reverse. Such a renderer is sent HLS anyway if its sink lists any video format (the old behaviour) and fails the item with the
  renderer's own reason if it refuses.
- Subtitles are not carried over HLS (the brain's WebVTT tracks are DASH adaptation sets); the master playlist's log line counts
  the tracks it left out.
- The ADR 174 sound-only notice (`over HLS; there is no picture yet`) is gone: a device that takes pictures is offered one.
- This HLS is the brain's own, built from a probed picture plus Totum's processed sound. A YouTube video that is offered only as
  YouTube's HLS (muxed MPEG-TS, no picture to probe) is a different thing: it is relayed as YouTube made it at
  `relay.m3u8` with content type `application/x-mpegURL`, for every format, and `hls.m3u8` is not involved (ADR 156, amended
  2026-11-28).

## Not proven, and why (read before relying on it)

- **The real Kodi was not running when this was built** (its ports refused connections; `JSONRPC.Version` could not be asked), so
  its add-on list, its answer to a real `Player.Open` of the brain's HLS, and whether `Player.PlayPause` with an explicit `play`
  and `Player.Seek` with a time behave as the documentation says were not read. The control follows Kodi's JSON-RPC
  documentation and a stand-in written from it.
- **HEVC and Apple's tag.** A remuxed MKV's HEVC sample entry is `hev1`; Apple's HLS rules want `hvc1`. The LG (webOS) and Kodi
  (ffmpeg) are not Apple, and the master's `CODECS` says what the init segment says, but a real Apple TV was never in the
  picture and an LG that insists on `hvc1` would refuse an HEVC MKV.
- **The real LG over AirPlay 2** (still unpaired), its `supportsUIForAudioOnlyContent=False`, and how it reads a master playlist
  with an alternate audio rendition: AVPlayer-family receivers do; the stand-in does not model a receiver's decoder.
- **A muxed fragmented MP4** (a file with its own audio track in the same fragments) is listed by byte range whole, so the
  device sees the file's audio and the processed rendition; DASH never showed the file's audio. A video-only stream (every
  YouTube rung) and the remux path are not affected.
- **A seek in Kodi's own player.** Kodi plays HLS through libavformat, whose input-side seek on fMP4 HLS failed here on this
  stream and on ffmpeg's own output (see the evidence). GStreamer's seek worked. If Kodi's seek misbehaves, the fix is on the
  playlist side (the brain would have to list what libavformat can seek), not in `KodiControl`.
- **`inputstream.adaptive`** and a DASH `Player.Open`: `TOTUM_KODI_FORMAT=auto` is an untried switch.
- **DIDL class.** `DlnaControl` still describes every item as `object.item.audioItem.musicTrack`; gmrender and Kodi's renderer do
  not care, a strict renderer might.

## Evidence

Run on this laptop on 2026-11-14, nothing audible (fake and null sinks), against the build in `cli/build/install` (`totum serve`
with `TOTUM_CAST_BASE` the docker bridge address, `TOTUM_DLNA_TARGETS`, `TOTUM_KODI_TARGETS`, `TOTUM_AIRPLAY_TARGETS`),
`tools/cast-bench/targets-e2e.py --video-url …` driving `/targets` (a film at an address the Pi probes by range, the deck's own
play, pause, play, seek, stop) and the films made with ffmpeg: a 30 s H.264 MP4 (not fragmented: remuxed), a 20 s HEVC MKV, and a
30 s fragmented MP4 with a `sidx` (byte-range), all 640x360 with a tone.

- **DLNA, gmrender-resurrect in Docker** (GStreamer; `--gstout-videosink fakesink`). Its sink lists `application/x-hls`; the control
  logged `video or HLS listed, so it is sent the picture` and loaded `…/hls.m3u8` as `application/x-hls`. The one stream server saw
  it fetch `hls.m3u8`, `hls-video.m3u8`, `hls-audio.m3u8`, `audio/init.mp4`, `video/init.mp4`, **all fifteen `video/<n>.m4s`**
  and the processed `audio/<n>.m4s`; PAUSE, PLAY, SEEK 15000 (read back 15009 ms), VOLUME 10 (the renderer's log shows only
  `CurrentVolume` reads before it) and STOP all went through. The fragmented MP4 made the same requests with the picture served
  by `Range` from the file (the origin logged the 206s). Caveat: gmrender's video sink is not synchronised, so it consumes the
  clip faster than real time (its position sits at the end of the clip until the seek); GStreamer found and decoded both
  streams and raised no error.
- **Kodi, a JSON-RPC stand-in** (`tools/cast-bench/kodi-standin`, **not Kodi**: Basic auth read from a 600 file at every call,
  `Player.Open` opens the address with ffmpeg, Kodi's own HLS demuxer, into a null sink in real time and logs what it found).
  `Player.Open {"item":{"file":"…/hls.m3u8"},"options":{"resume":false}}` found **h264 640x360 and aac** (the HEVC MKV: **hevc
  640x360 and aac**). The deck's position followed the stand-in's own clock; `Player.PlayPause play=false` held it (7.8 s) and
  `play=true` resumed from there; `Player.Seek` carried `{"hours":0,"minutes":0,"seconds":15,"milliseconds":0}`; the volume
  slider was the only `Application.SetVolume` and everything before it was `Application.GetProperties`; `Player.Stop` ended it.
  The fake password appears in no log. One device named in both lists (Kodi and DLNA): `driven over kodi (tried 0 before it)`;
  with the stand-in stopped, `now driven over dlna, not kodi (unreachable: kodi)` and gmrender played it.
- **AirPlay, pyatv 0.18's `airplay_helper.py` against the v1 stand-in, now taking a master playlist.** The helper's ready event
  said `pictures accepted` (the stand-in's features `0x1F` have bit 0). The stand-in fetched the master, saw a picture
  (`640x360`, `avc1.64001E,mp4a.40.2`) and a sound rendition, fetched `video` as `1277@0` (the init span, an `ftyp` box) and every
  picture and sound segment (`moof`), and received `POST /rate 0`, `/rate 1`, `/scrub position=15.0`. Its own full ffmpeg decode of
  the master: **750 frames, no errors**. An HEVC film on the same device (an unmeasured screen, assumed H.264 only) became
  `📺 AirStand gets sound only: its video is 360p · HEVC, beyond what AirStand can show`, and the stand-in was handed the
  sound-only playlist.
- **ffmpeg 6.1.1 directly on the master**: a whole decode, no warnings; init plus segment 7 of the video by hand, clean (pts 14.08 s);
  an output-side `-ss 15`, 375 frames, clean. **An input-side `-ss 15` through libavformat's HLS demuxer failed on this stream
  (garbled NAL sizes) and also on a control, ffmpeg's own HLS fMP4 muxer output (no frames at all), with the files served with
  and without `Range`.** I read that as libavformat's seek on fMP4 HLS, not the Pi's segments, and the GStreamer seek above
  worked, but it is why Kodi's seek is listed below.

## Tests

`SidxTest`, `CastFormatTest`, `HlsPlaylistTest`, `CastStreamServerHlsPictureTest` (`:core:cast`); `KodiControlTest` (a fake Kodi
over real HTTP: every request body, the sign-in header and that it never reaches a log), `DlnaControlTest`, `CastDevicesTest`,
`ChromecastPlayerTest` (`:cli`); `airplay_helper_test.py` (`ReadyTest`).
