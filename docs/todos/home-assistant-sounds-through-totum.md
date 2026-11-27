---
title: 🔊 Home Assistant's brown noise, jazz and shows play through Totum
kind: todo
status: adapter LIVE on the Pi since 2026-11-07 (redeployed 2026-11-13); brought up to date and contract-tested 2026-11-20; jazz switched to SomaFM Sonic Universe 2026-11-27 (code and tests done, redeploy owed, runbook below); first real HA-driven play on a speaker still unseen
batch: A (2026-11), C (2026-11)
priority: high
updated: 2026-11-27
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

## Jazz: SomaFM Sonic Universe (decided 2026-11-27, built, not deployed)

Dewi chose option 2 of the 2026-11-20 list: a **non-YouTube internet radio**, so jazz depends on nothing that can vanish the way the
first YouTube live did, and on nothing YouTube can refuse the Pi.

- **The station:** SomaFM's Sonic Universe, `https://ice1.somafm.com/sonicuniverse-128-mp3` (`STATIONS["jazz"]` in
  `tools/handoff/hermes-audio/audio-ctl.py`). `sRBkcMhnT_A` (dead) and `Dx5qFachd3A` (the 24/7 YouTube live that was never played
  through Totum) are gone from the adapter. Use `ice1` only: on 2026-11-20 `ice2` answered 403.
- **The queue item the adapter now sends:** `itemId "station:jazz"`, `sourceId "hermes-audio-stations"`, `title "Jazz station"`,
  `contentKind "MUSIC"`, `playbackType "PODCAST"`, `handle "audio=https://ice1.somafm.com/sonicuniverse-128-mp3"`, `mediaUrl` the same
  address, `loop false`. `MUSIC` so the deck never runs skip-silence or boost through ffmpeg on a radio (music plays plain); `PODCAST`
  with an `audio=` handle because that is how a `PlayableItem` carries a plain audio address. `media_of` names the station by its
  `station:jazz` item id, so `GET :8099/status` still says `media.station == "jazz"` for `switch.jazz` and the voice automations,
  and an old `Dx5qFachd3A` entry left in a deck is simply an ordinary item (HA's `/stop` still removes every entry the adapter added).
- **Does the Pi's target path play a plain HTTP MP3 stream? Yes, checked three ways (2026-11-27):**
  1. *Read in the code.* `TargetStreams.urlFor` finds no `file:` path, no Jellyfin id, so it asks `HomeDownloads.soundOf`; for a
     `PlayHandle.Podcast` that returns `handle.audioUrl ?: item.mediaUrl` through `Rewrites` (none match), with no yt-dlp, no
     YouTube call, and no young-URL wait (that rule needs an `expire=` in the address). `StreamRelay` carries only
     `googlevideo.com/videoplayback` addresses, so the radio is given to mpv as it is. `MpvPlayer` then runs
     `mpv --no-video … --audio-device=pulse/<sink> … --start=0.0 -- <address>` unprocessed (HA sets no sound settings).
  2. *Tests.* `YouTubeGateTest` (new): `soundOf` of that item returns the exact address and nothing reaches YouTube or yt-dlp.
     `MpvPlayerTest` (new): the argv ends `-- https://ice1.somafm.com/sonicuniverse-128-mp3`, no `--demuxer`, no `--volume`.
     `HermesAdapterContractTest` (new): the adapter's own jazz request, posted to the real route for a reachable speaker, is
     accepted, goes to the speaker's player, is attributed to "🏠 Home Assistant", does not loop, and keeps its `audio=` handle.
  3. *A real mpv on the real station, from the laptop, 10 seconds, with the production flags and a null audio device:*
     `mpv --no-video --no-input-terminal --msg-level=all=info --idle=no --ao=null --start=0.0 -- <station>` printed
     `Audio --aid=1 (mp3 2ch 44100Hz)`, `Cannot seek in this stream` (harmless: it is a live stream, so `--start=0.0` is a no-op),
     the `icy-title` of the track playing, and was still playing when `timeout` stopped it (exit 124). A fake server that
     rejects ranged requests made mpv fail with "HTTP error 403" (exit 2), the exact failure the old todo feared; the real
     SomaFM answers mpv's own requests, so it does not apply.
  **Not done:** a play on a real Bluetooth speaker, and mpv on the Pi itself (the laptop's mpv is not the Pi's version). Both are
  steps 6 and 7 of the runbook.
- **Seen red first:** `test_audio_ctl.py` failed on `AssertionError: 'station:jazz' != 'Dx5qFachd3A'` and on the
  `STATIONS` dictionary still holding the YouTube address; the two golden fixtures were regenerated
  (`adapter-requests.json` from Python, `targets-jazz-speaker-unreachable.json` from `HermesAdapterContractTest`) and
  `test_audio_ctl_contract.py` failed with `KeyError: 'station'` against the old golden file in between.
- **Where the sound is checked:** the Soundmic is the speaker HA's jazz is pinned to (`?speaker=soundmic`) and was out of
  Bluetooth range on 2026-11-20, so the first real play uses `?speaker=soundbar`.

## Runbook: deploy the updated adapter on the Pi (the orchestrator runs it; nothing here has been run)

The adapter is already live, so this swaps one file. **Totum itself needs no deploy:** the Pi's `totum serve` already plays a
podcast handle's `audio=` address through mpv and already answers `understandsStarting` (since 2026-11-14). The file is written
to be safe on a Pi still running the 7 Nov or 13 Nov adapter: the steps are the same either way, and the adapter holds no
state (after a restart it reads everything from `GET /targets`, and anything playing keeps playing).

1. **From the laptop, copy the new file** to the dot-files copy (the Pi's tracked source of the deployed file):
   `scp tools/handoff/hermes-audio/audio-ctl.py pi@192.168.0.19:code/dot-files/hermes-audio/audio-ctl.py`
2. **On the Pi, check it parses and note the state before** (read only):
   `python3 -m py_compile ~/code/dot-files/hermes-audio/audio-ctl.py && grep -c 'station:jazz' ~/code/dot-files/hermes-audio/audio-ctl.py`
   (expect `1` or more), `curl -s 127.0.0.1:8099/status`, `curl -s 127.0.0.1:8772/health`,
   `md5sum /var/lib/hermes-audio/audio-ctl.py` (the md5 of what is live now, for the roll-back note).
3. **Check the station answers the Pi's own mpv, before swapping anything** (a 10-second read, no sound: a null audio device):
   `timeout 10 mpv --no-video --no-input-terminal --msg-level=all=info --idle=no --ao=null --start=0.0 -- https://ice1.somafm.com/sonicuniverse-128-mp3; echo exit=$?`
   Expect `Audio --aid=1 (mp3 2ch 44100Hz)`, an `icy-title`, and `exit=124` (still playing when stopped). `exit=2` with
   "HTTP error 403" or "Failed to open" means this Pi's address or mpv is refused: stop here and tell Dewi.
4. **Back up the live file and swap it, then restart the adapter:**
   `sudo cp -p /var/lib/hermes-audio/audio-ctl.py /var/lib/hermes-audio/audio-ctl.py.pre-20261127 &&
   sudo install -m 755 -o root -g root ~/code/dot-files/hermes-audio/audio-ctl.py /var/lib/hermes-audio/audio-ctl.py &&
   systemctl --user restart hermes-audio-ctl`
5. **Check it (read only):** `systemctl --user status hermes-audio-ctl --no-pager | head -5`;
   `journalctl --user -u hermes-audio-ctl --no-pager -n 5` shows
   `dewidebug audio-ctl listening on 127.0.0.1:8099, playing through Totum at http://127.0.0.1:8772`;
   `curl -s 127.0.0.1:8099/status` returns the same keys as before (`state, starting, want, media, bonded, link, keepalive,
   holding_silence, last_error, sounds, stations, speaker, speakers`) with `"stations": ["jazz"]`;
   `md5sum /var/lib/hermes-audio/audio-ctl.py ~/code/dot-files/hermes-audio/audio-ctl.py` agree.
6. **Commit the dot-files copy by path only**, never `git add -A` (an agent also commits there):
   `git -C ~/code/dot-files add hermes-audio/audio-ctl.py && git -C ~/code/dot-files commit -m "hermes-audio: jazz is SomaFM Sonic Universe, a plain mp3 radio (Totum todo)"`.
7. **The first real test** (it makes sound: at a quiet moment, with the speaker's own volume checked first, and Dewi told). Jazz on
   the soundbar, because the Soundmic may be out of range:
   - `curl -s -X POST '127.0.0.1:8099/play/jazz?speaker=soundbar'`, then after about five seconds `curl -s 127.0.0.1:8099/status`
     should say `"state": "playing"` and `"media": {"station": "jazz", …}`; `curl -s 127.0.0.1:8772/targets` shows an entry
     `station:jazz` added by "🏠 Home Assistant" with `loop: false` on the soundbar, and the Queue tab shows it;
     the Pi's `totum serve` log (find its unit with `systemctl --user list-units 'totum*'`; the name and the targets directory
     holding `mpv-<id>.log` were not looked up here) shows `station:jazz: … -> a stream` and
     `mpv <pid> on <sink> … stream`, and mpv's own log has no `Failed to open` line;
   - let it run five minutes: a radio does not end, so the entry should still be `playing`; if mpv exits, the deck says why in
     `problem` and `last_error` in `/status` carries it;
   - `curl -s -X POST 127.0.0.1:8099/stop` stops it and removes only the adapter's entry;
   - the same from HA's `switch.jazz` (pinned to the Soundmic, so bring that into range first), and `switch.brown_noise` as before;
   - brown noise: `POST :8099/play` still loops after five minutes; shows with `POST :8099/play-show/msrachel?mode=latest`.
8. **Roll back:** `sudo cp -p /var/lib/hermes-audio/audio-ctl.py.pre-20261127 /var/lib/hermes-audio/audio-ctl.py && systemctl --user
   restart hermes-audio-ctl`, and `git -C ~/code/dot-files checkout -- hermes-audio/audio-ctl.py` if the dot-files copy was not
   committed yet (or revert the commit). The pre-Totum standalone service is still at
   `/var/lib/hermes-audio/audio-ctl.py.pre-totum-20261107` if anything bigger is ever needed, and putting it back also means
   Totum no longer owns the speakers (dot-files ADR 0009).

## Done when

Brown noise, jazz and a show each start from HA on each speaker at the speaker's own low level, appear in Totum's queue, stop
from HA and from the phone, and `switch.brown_noise` / `switch.jazz` report the right state; brown noise still loops after
5 minutes. Not yet seen on a real speaker.
