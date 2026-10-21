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
