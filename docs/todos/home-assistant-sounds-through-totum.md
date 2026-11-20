---
title: 🔊 Home Assistant's brown noise, jazz and shows play through Totum
kind: todo
status: adapter LIVE on the Pi since 2026-11-07 (redeployed 2026-11-13); brought up to date and contract-tested 2026-11-20 (redeploy owed); first real HA-driven play on a speaker still unseen; jazz source open (Dewi's decision)
batch: A (2026-11), C (2026-11)
priority: high
updated: 2026-11-20
---

# 🔊 Home Assistant's brown noise, jazz and shows play through Totum

Dewi, 2026-11-07: Totum owns the Bluetooth speakers (dot-files ADR 0009), and the move of Home Assistant's sounds onto
it is "this batch please". The Totum side was built on 7 Nov ([ADR 155](../adr/0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md),
[ADR 158](../adr/0158-targets-end-of-batch-fixes.md)).

## A correction to the earlier status (2026-11-20)

This file said "adapter not deployed". **It has been live since 2026-11-07.** Read on the Pi, read only, 2026-11-20:

- `hermes-audio-ctl` is a **user** unit of `pi` (`~/.config/systemd/user/hermes-audio-ctl.service`), running
  `/usr/bin/python3 /var/lib/hermes-audio/audio-ctl.py` since 2026-11-13 19:26, talking to `http://127.0.0.1:8772`.
- `/var/lib/hermes-audio/audio-ctl.py` (root-owned, 16,559 bytes) has the same md5 as the Pi's dot-files copy
  (`~/code/dot-files/hermes-audio/audio-ctl.py`) and as `tools/handoff/hermes-audio/audio-ctl.py` on `main` before this change
  (`9b81ad32…`). So the adapter is what this repo's handoff says it is.
- The roll-back copy of the old standalone service is still there: `/var/lib/hermes-audio/audio-ctl.py.pre-totum-20261107`.
- `GET 127.0.0.1:8099/status` answers HA's shape; `GET 127.0.0.1:8772/targets` lists five targets (the two speakers, the LG TV,
  the living-room Chromecast, Kodi), and its states carry exactly the 22 fields `TargetState` had then (this change adds a 23rd, `plays`, which the adapter ignores).
- The journal since 8 Nov holds only "could not read Totum's targets" lines at the moments `totum-fetch` restarted (connection
  reset or refused) and **no play, stop or show line at all**, and the speakers' saved queues hold no "🏠 Home Assistant" entry.
  So no HA-driven play has been logged since the journal begins (2026-11-08 07:56). The done-when below is therefore **unproven**,
  not "done".

## What HA calls, and the adapter keeps answering (re-read 2026-11-20 from the Pi's `homeassistant/config`)

- `switch.brown_noise`: `POST :8099/play`, `POST :8099/stop`, state from `GET :8099/status` (`state == playing` and
  `want == brown-noise`); `switch.jazz`: `POST :8099/play/jazz?speaker=soundmic`, state from `media.station == jazz`;
  `sensor.show_audio` reads `state` every 5 s (the `show_*` script waits for it to say `playing`); the rest commands
  `show_audio_play` (`POST :8099/play-show/<channel>?mode=`) and `show_audio_stop` (`POST :8099/stop`); the voice automations
  (`audio_voice_jazz`, `audio_voice_jazz_stop`) and the kids' dashboard only toggle those two switches.
- Shows are resolved by firetv-ctl's `127.0.0.1:8100/resolve/<channel>?mode=`, which still answers
  `{id, title, published, url, channel, mode}` (checked 2026-11-20), the shape the adapter reads.
- Nothing in HA changed, and nothing in HA needs to.

## The adapter's contract with Totum, now tested (2026-11-20)

- **Changed in the adapter:** every queue request says `"understandsStarting": true`. Totum answers such a request after at most
  1.5 s with `starting: true` and the adapter's own wait (`_started`, written for exactly this) reads `GET /targets` until the
  start has ended; a request without the field makes the Pi hold the HTTP answer for up to 55 s and answer "playing" (the old-phone
  rule, ADR 158). Seen red first: `test_play_queues_brown_noise_...` failed on `KeyError: 'understandsStarting'`. Safe on any Pi: an
  older one ignores the field.
- **Nothing else needed changing for ADRs 152 to 177.** The adapter reads only `target.id`, `entries[].item/addedBy/loop`, `at`,
  `playing`, `linked`, `problem`, `starting` and `trouble`; the new fields (`volume`, `volumeMode`, `keepAwake`, `power`, `sleep`,
  `startsApp`, `canSwitchOff`, `plays`) are ignored. A play never sets a volume (ADR 152, 165), so the "3%" in the done-when is
  **the speaker's own level**, which Totum leaves alone; check it before the first test.
- **Contract tests, both directions** (`tools/handoff/hermes-audio/fixtures/`):
  - `adapter-requests.json` is what the adapter sends for brown noise, jazz and a show. `test_audio_ctl_contract.py` fails if the
    adapter's requests change; `HermesAdapterContractTest` (`:cli`) posts each to the **real** `TargetRoutes` and `TargetDecks`
    (a fake player, the real `SoundFiles` rule over a temporary `/sounds`) and checks it is accepted, plays the file or stream,
    is attributed to "🏠 Home Assistant", loops by itself and leaves the deck's repeat alone.
  - `targets-brown-noise-playing.json` and `targets-jazz-speaker-unreachable.json` are what that real route answers to
    `GET /targets` afterwards (golden files, regenerated with `TOTUM_REGEN_FIXTURES=1`). The Python tests run the adapter's
    `/status` and `/stop` logic over them, and the Kotlin test fails if the route's shape stops matching them.
  - To change the adapter: edit it, run `TOTUM_REGEN_FIXTURES=1 python3 -m unittest tools/handoff/hermes-audio/test_audio_ctl_contract.py`
    to rewrite the requests, then `./gradlew :cli:test --tests '*HermesAdapterContractTest*'`.

## Two things found on the live Pi (2026-11-20)

- **The soundbar's deck is stuck on `repeat: ONE`** (the live `GET /targets` says so, 150 entries). It was left by the 7 Nov stopgap
  adapter (ADR 158 says a deck left on ONE keeps it until someone changes it). Every phone item sent to the soundbar would repeat,
  and so would a HA show or station (brown noise loops by its own entry flag and is unaffected). One line fixes it and is a
  write, so it waits for Dewi's go: `curl -s -X POST '127.0.0.1:8772/targets/control?id=54158976cda4' -H 'Content-Type:
  application/json' -d '{"action":"REPEAT","repeat":"OFF"}'` (or the Remote queue's repeat picker on the phone).
- **The Soundmic is not reachable right now**: its state says `power ASLEEP`, `keepAwake RECONNECTING`, `linked false`. HA's
  jazz is pinned to it, so a Jazz tap would answer 200 and then show `last_error` "📶 Couldn't reach dprofy Soundmic" (that is the
  second contract fixture). The Pi's notes say it is out of Bluetooth range from its usual spot.

## Jazz: the station (open, Dewi's decision)

Checked 2026-11-20 20:20 from the laptop with yt-dlp 2026.07.04, two metadata requests:

- `sRBkcMhnT_A` (the old station): still "This live stream recording is not available". Dead. It is no longer in the adapter.
- `Dx5qFachd3A` (what the adapter already plays, chosen 7 Nov): live now (`is_live`, the title carries the clock,
  "Relaxing Jazz Piano Radio … 24/7 Live Stream", channel Cafe Music BGM). **It has never been played on a speaker through Totum**
  (no log, no queue entry), and a 24/7 YouTube live stream can vanish exactly as the first one did. It also depends on YouTube
  not refusing the Pi (the target says "⏳ YouTube is refusing the Pi for now" when it does, ADR 156).
- Options for Dewi to choose between:
  1. **Keep `Dx5qFachd3A`** and watch the first real play. Nothing to change. Fragile the same way the first one was.
  2. **A non-YouTube internet radio**, no YouTube in the way: Totum already plays a `Podcast` handle's `audio=` address through mpv
     (`HomeDownloads.soundOf`). Example found, not played through Totum: SomaFM's Sonic Universe,
     `https://ice1.somafm.com/sonicuniverse-128-mp3`, which answered `200 audio/mpeg` to a plain GET with a player user agent from
     the laptop (it answered 403 to a ranged request and from `ice2`, so test it on the Pi first). The adapter change is small:
     `STATIONS["jazz"]` becomes an item with `playbackType "PODCAST"`, `handle "audio=<address>"`, `mediaUrl` the address,
     `contentKind "MUSIC"`, and `media_of` names it by `itemId` as it does now.
  3. **Both**, jazz trying the radio first and the YouTube station if Totum reports a problem: more code in the adapter, and a
     second decision about which one wins.
- Recommendation: 2, for a station nobody has to babysit. Not changed here: it is Dewi's call.

## Runbook: deploy the updated adapter on the Pi (the orchestrator runs it; nothing here has been run)

The adapter is already live, so this swaps in the one-line change (and keeps the Pi's dot-files copy the same file). Totum itself
needs no deploy for this: it already answers `understandsStarting` (since 2026-11-14; the Pi runs 0.1.941).

1. From the laptop, copy the new file to the dot-files copy (the Pi's tracked source of the deployed file):
   `scp tools/handoff/hermes-audio/audio-ctl.py pi@192.168.0.19:code/dot-files/hermes-audio/audio-ctl.py`
2. On the Pi, check it parses, and note the state before: `python3 -m py_compile ~/code/dot-files/hermes-audio/audio-ctl.py &&
   curl -s 127.0.0.1:8099/status; curl -s 127.0.0.1:8772/health`.
3. Back up the live file and swap: `sudo cp -p /var/lib/hermes-audio/audio-ctl.py /var/lib/hermes-audio/audio-ctl.py.pre-20261120 &&
   sudo install -m 755 -o root -g root ~/code/dot-files/hermes-audio/audio-ctl.py /var/lib/hermes-audio/audio-ctl.py &&
   systemctl --user restart hermes-audio-ctl`. (The adapter holds no state that matters: after a restart it reads everything
   from `/targets`, and anything playing keeps playing.)
4. Check it (read only): `systemctl --user status hermes-audio-ctl --no-pager | head -5`; `journalctl --user -u hermes-audio-ctl
   --no-pager -n 5` shows `dewidebug audio-ctl listening on 127.0.0.1:8099, playing through Totum at http://127.0.0.1:8772`;
   `curl -s 127.0.0.1:8099/status` returns the same keys as before (`state, starting, want, media, bonded, link, keepalive,
   holding_silence, last_error, sounds, stations, speaker, speakers`); `md5sum /var/lib/hermes-audio/audio-ctl.py
   ~/code/dot-files/hermes-audio/audio-ctl.py` agree.
5. Commit the dot-files copy by path only, never `git add -A` (an agent also commits there): `git -C ~/code/dot-files add
   hermes-audio/audio-ctl.py && git -C ~/code/dot-files commit -m "hermes-audio: queue requests say they understand a slow start (Totum todo)"`.
6. The first real test (it makes sound, so at a quiet moment, with the speaker's own volume checked first and Dewi told):
   - `curl -s -X POST 127.0.0.1:8099/play` then, after a few seconds, `curl -s 127.0.0.1:8099/status` should say
     `"state": "playing"` and `"want": "brown-noise"`; `curl -s 127.0.0.1:8772/targets` shows an entry
     `sound:brown-noise` added by "🏠 Home Assistant" with `loop: true` on the soundbar, and the Queue tab shows it;
   - `curl -s -X POST 127.0.0.1:8099/stop` stops it and removes only that entry;
   - the same from HA's switch, then wait five minutes and check it still loops (the done-when);
   - jazz on the soundmic needs the Soundmic in range; shows with `POST :8099/play-show/msrachel?mode=latest`.
7. Roll back: `sudo cp -p /var/lib/hermes-audio/audio-ctl.py.pre-20261120 /var/lib/hermes-audio/audio-ctl.py && systemctl --user
   restart hermes-audio-ctl`, and `git -C ~/code/dot-files checkout -- hermes-audio/audio-ctl.py` if the dot-files copy was
   not committed yet (or revert the commit). The pre-Totum standalone service is still at
   `/var/lib/hermes-audio/audio-ctl.py.pre-totum-20261107` if anything bigger is ever needed, and putting it back also means
   Totum no longer owns the speakers (dot-files ADR 0009).

## Done when

Brown noise, jazz and a show each start from HA on each speaker at the speaker's own low level, appear in Totum's queue, stop
from HA and from the phone, and `switch.brown_noise` / `switch.jazz` report the right state; brown noise still loops after
5 minutes. Not yet seen on a real speaker.
