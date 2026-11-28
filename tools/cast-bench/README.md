# Cast bench

Casts Totum from the `totum-api35` emulator to a real TV on the home LAN and checks the result in the room.
See ADR 68 and `docs/features/casting.md`.

1. `capture-tv.sh [tv-ip]` — makes the emulator's Cast dialog list that TV (mDNS replay; the emulator's NAT
   blocks real discovery).
2. `pi-hop.sh` (leave running) — the TV fetches Totum's stream through the Pi: `adb forward tcp:8765 tcp:8765`,
   an ssh reverse tunnel to `192.168.0.19:18765`, and a temporary firewall rule for the TVs only, deleted on exit
   (6 h backstop). Then `run-as com.dewijones92.totum sh -c 'echo http://192.168.0.19:18765 > files/cast-base-url.txt'`.
3. `bench.sh play <ids>`, `bench.sh cast [name]`, `bench.sh stop`, `bench.sh tv`, `bench.sh snap <file>` (the Pi's
   webcam faces the LG), `bench.sh log [regex]` — no screen taps.

The room mic (`arecord -D plughw:1,0` on the Pi) measures sound; raise the TV volume only briefly.

`bench.sh until [wait|seek <s>]` (on the Pi, from the receiver's own status) times how long the TV takes to really
play: PLAYING with its clock 0.5 s past where it first said so.

If `pi-hop.sh` is stopped from this side, its remote half notices on its next heartbeat (within a minute) and deletes
the firewall rule; check with `sudo nft -a list chain ip filter INPUT | grep dewi-temp-cast-bench` on the Pi.

## Stand-in receiver (no TV needed)

`standin/standin.sh [plan] [model]` plays what Totum would cast in **Shaka Player** (what the Cast receiver plays DASH
with) inside a private headless Edge on Windows — never Dewi's own browser — fed through `adb forward` to the
emulator. Play something in the app first (`bench.sh play <id>`). The plan is comma-separated steps: `start`,
`seek:<s>`, `subs:<lang>`, `subs:off`, `wait:<s>`; each prints how long until the video really plays, or the cue
shown. `model` makes Totum judge formats as that Cast device (`"Google Nest Mini"` exercises the sound-only path);
`model=none` through `adb shell am broadcast -a com.dewijones92.totum.DEBUG_CAST_SERVE -p com.dewijones92.totum --es
model none` clears it. Needs Node and `playwright-core` in `C:\edge-cdp` (see the WslEdgeDriver rig).

`am broadcast -a com.dewijones92.totum.DEBUG_CAST_SCREEN -p com.dewijones92.totum --es model "'Chromecast'"` (or `none`) makes the app
treat the cast target as that model, so the move-to-the-TV replay builds the ladder for it (wait for `replay for the TV=` in the log,
then start the stand-in); without it the stand-in judges formats as the model but the item's own ladder is the phone's.

It covers the receiving side: manifest, segments, seeks, start time, subtitles, sound only. It cannot cover the Cast
session (queue moves, the TV remote, ends): Android checks a Cast device's Google-signed certificate, so nothing
here can pose as one.

## A friend's Chromecast fetching from the home server (ADR 182)

`standin/public-standin.sh [plan]` runs the real `totum serve` (build it with `./gradlew :cli:installDist`) against a fake Jellyfin and a
generated film, mints a public cast session exactly as the phone does, plays the link's manifest in the Shaka stand-in (a private Edge
page of `about:blank`, so every request is cross-origin and CORS really applies), then ends the cast and checks the link is 404. Needs
ffmpeg, python3, curl and the Edge rig. TLS is nginx's job on the Pi and is not exercised; `tools/handoff/cast-out/` has the nginx patch
that was checked with a local `nginx -c`.

`screen/public-screen.sh [seconds]` does the same for a PC screen (ADR 182, amended 2026-11-28): the real `screen.html` is served by
`screen/phone-screen-standin.py` (a stand-in for the phone's page server) and a headless Edge plays the public link from the gate on another
origin. `MODE=phone` sends the load the old way, without `"direct":true`, and fails with Shaka error 1001.

## DLNA and AirPlay stand-ins (no device needed, silent)

ADR 174. `discover-dlna.py [host]` prints `name=description-url` lines for `TOTUM_DLNA_TARGETS` (SSDP; run it on the Pi host,
or unicast to one host).

- **DLNA**: gmrender-resurrect in Docker with fake sinks, so nothing is heard:
  `docker build -t totum-dlna-standin-gmediarender` from a Dockerfile that installs `gmediarender` and the GStreamer plugins, then
  `docker run -d --name totum-dlna-standin -p 49494:49494 totum-dlna-standin-gmediarender gmediarender --port 49494
  --gstout-audiopipe "fakesink sync=true" --gstout-videosink fakesink --logfile stdout`. Its description is
  `http://127.0.0.1:49494/description.xml`; point a locally built `totum serve` at it with `TOTUM_DLNA_TARGETS`, and set
  `TOTUM_CAST_BASE` to an address the container can reach (the docker bridge gateway, `172.17.0.1`).
- **AirPlay**: `airplay-standin/standin.py --address <a local non-loopback address>` (needs pyatv's venv: aiohttp-free, uses
  `zeroconf`) is an AirPlay v1 receiver that answers `/play`, `/playback-info`, `/rate`, `/scrub` and `/stop`, fetches the
  playlist and segments it is given and plays nothing. pyatv's unicast scan finds it (mDNS is not answered on 127.0.0.1).
  It models the endpoints from pyatv's client code, not from a real receiver.

## Picture over HLS, and Kodi (ADR 175), silent

- `serve-ranges.py <dir> [--port N]` serves a folder with byte ranges (the brain probes and relays a film at an address by range).
- **Kodi**: `kodi-standin/standin.py --credentials <file of user=/password= lines> [--port 8081] [--adaptive]` answers Kodi's
  JSON-RPC (`JSONRPC.Version`, `Addons.GetAddons`, `Application.GetProperties`/`SetVolume`, `Player.Open`, `PlayPause`, `Seek`,
  `Stop`, `GetActivePlayers`, `GetProperties`) with Basic auth read from the file at each call, and plays what it is opened with
  by decoding it into a null sink with ffmpeg, **the same HLS demuxer Kodi uses**, in real time. It logs the streams it found
  (video codec and size, audio codec): that line is the proof a picture and sound arrived. It is a stand-in, not Kodi.
  `TOTUM_KODI_TARGETS=Kodi=127.0.0.1:8081 TOTUM_KODI_CREDENTIALS=<the same file>`.
- **DLNA with video**: the gmrender container above already has `--gstout-videosink fakesink`, so GStreamer decodes the picture
  and nothing is shown.
- **AirPlay**: `airplay-standin/standin.py --decode` follows a master playlist (the picture and the sound rendition, init
  segments and byte ranges included), and with `--decode` also decodes the whole stream with ffmpeg and reports the streams.
- `targets-e2e.py --target <id> --video-url <address the Pi can read> --duration-ms <n>` queues a video instead of the tone and
  runs the same play, pause, play, seek, stop drive.

