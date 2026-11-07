---
title: 🔊 Home Assistant's brown noise, jazz and shows play through Totum
kind: todo
status: refined 2026-11-07, Batch A
batch: A (2026-11)
priority: high
updated: 2026-11-07
---

# 🔊 Home Assistant's brown noise, jazz and shows play through Totum

Dewi, 2026-11-07: Totum owns the Bluetooth speakers (dot-files ADR 0009), and the move of Home Assistant's sounds onto
it is "this batch please".

## Today (read 2026-11-07)

- `hermes-audio-ctl` (dot-files `hermes-audio/audio-ctl.py`, host, `127.0.0.1:8099`) runs its own mpv and
  `bluetoothctl connect/disconnect`. HA calls it from `homeassistant/config/configuration.yaml`: `switch.brown_noise`
  (`/play`, `/stop`, `/status`), `switch.jazz` (`/play/jazz?speaker=soundmic`), a status sensor, and the
  `play_show` / `stop` rest commands (`/play-show/<channel>?mode=`; shows resolved by firetv-ctl's
  `127.0.0.1:8100/resolve`). The switches read `/status`'s `want` and `media.station`.
- Brown noise is a local file, `/var/lib/hermes-audio/brown-noise.opus`, looped by mpv.
- **The jazz station is dead**: `sRBkcMhnT_A` answers "This live stream recording is not available" to every client
  (Totum's resolver, 2026-11-07 07:35). HA's Jazz button fails today whatever plays it.
- Totum's `totum-fetch` publishes only 8771 (casts); 8770 (targets) is reachable only on the `bin_private` network, so
  nothing on the host (HA, `hermes-audio-ctl`) can call `/targets`.
- Totum's targets have no repeat, and a target that passes over an unplayable item shows no problem.
- **A live YouTube stream does not play on a Pi target** (2026-11-07 07:36, `Dx5qFachd3A`, a live jazz radio): it
  resolved through the signed-in TV client (16 formats) and mpv exited 2 at once. The phone plays live through a manifest
  it builds (ADR 69: head request, `&sq=$Number$` segments on loopback); the Pi hands the raw URL over.
- A target's queue keeps earlier items: a PLAY after STOP showed "playing 1/3". Fine, but a passed-over or failed item
  shows `problem: null`.

## Decision (the coordinator's call; Dewi delegated it)

- **`hermes-audio-ctl` becomes a thin adapter**: same URLs and the same `/status` shape (`state`, `want`,
  `media.station`, `speaker`), so HA's config, voice phrases and dashboard do not change. It no longer runs mpv or
  `bluetoothctl`; every play, stop and speaker choice is a request to Totum's `/targets`, and `/status` is read from
  `GET /targets`. Speaker names map to target ids (`soundbar` 54158976cda4, `soundmic` f44efd21926d).
- **8770 published on the Pi's loopback only** (`127.0.0.1:8770`), the same trust as 8099 today.
- **Repeat on a target** (off / this one / the queue, as ADR 27 on the phone): brown noise is queued with repeat one.
- **A sound on the Pi**: brown noise is served to Totum as a file the Pi already holds (the container mounts
  `/var/lib/hermes-audio` read-only); one rule for "an item whose bytes are on the Pi".
- **Live on a target**: the Pi builds and serves the same live manifest the phone builds (ADR 69's `LiveDash`, pure
  JVM), for speakers and TVs alike.
- **Jazz**: a new 24/7 station (candidate `Dx5qFachd3A`, Cafe Music BGM channel's Relaxing Jazz Piano Radio, live on
  2026-11-07), checked to play for minutes, not seconds; the dead one is logged.
- **A passed-over item is a problem on the target** ("❌ Couldn't play Jazz station: not available"), shown on the
  phone's chip like any other.
- Items started by HA show in Totum's queue view, from "🏠 Home Assistant".

## Done when

Brown noise, jazz and a show each start from HA on each speaker at 3%, appear in Totum's queue, stop from HA and from
the phone, and `switch.brown_noise` / `switch.jazz` report the right state; brown noise still loops after 5 minutes.
