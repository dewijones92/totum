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

It covers the receiving side: manifest, segments, seeks, start time, subtitles, sound only. It cannot cover the Cast
session (queue moves, the TV remote, ends): Android checks a Cast device's Google-signed certificate, so nothing
here can pose as one.
