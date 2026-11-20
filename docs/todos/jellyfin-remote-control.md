---
title: 🎞️ Drive a running Jellyfin app from Totum
kind: todo
status: built 2026-11-20, Batch C (ADR 187; unit tests against a fake Jellyfin; not run against a real Jellyfin client)
batch: C (2026-11)
priority: medium
updated: 2026-11-20
---

# 🎞️ Drive a running Jellyfin app from Totum

Dewi, 2026-11-20: drive what the Jellyfin app on the Fire Stick or the LG, or the web client, is playing from Totum, as one
more target. [ADR 187](../adr/0187-a-running-jellyfin-app-is-a-target-the-pi-drives-by-item-id.md) has the decision and the
evidence.

## What was built

- `TOTUM_JELLYFIN_CLIENTS=<label>=<device name contains, or exact device id>[;...]` makes each named client a target
  (`jellyfin-<label>`, kind TV on the wire), listed whether or not the app is open. Unset, nothing changes.
- The Pi sends `PlayNow` with the item id and start position, then watches the client's session (`RendererWatch`, one cached
  `GET /Sessions`): playing, paused, position, length, volume, "the item is over", "another item is playing", "the app was
  closed". Pause, resume, seek, volume and stop are the client's own commands.
- Power: `ON` while Jellyfin lists a controllable session for it, `ASLEEP` otherwise, by hand ("🔌 Jellyfin is closed · open it
  by hand").
- Only Jellyfin items: the Pi refuses the rest with a reason, the phone's picker says "🎞️ Jellyfin films and episodes only"
  and a batch with anything else in it plays on the phone instead. The Sessions API has no way to give a client an address.
- Files: `cli/.../serve/jellyfin/JellyfinSessions.kt` (parser, API calls, the device matcher, the cached board),
  `JellyfinClientTarget.kt` (player, link, source), `TargetPlays` and `PlayOn` hints in `:core:data`, the deck's refusal in
  `TargetDeck`, the redirect rule in `TargetsRepository`.

## Open

- **Not run against a real client.** On 2026-11-20 Jellyfin listed one session, a phone app that was not controllable; the
  Fire Stick app was not running. After deploying, play one film (see the runbook) and read `dewidebug` lines tagged
  `jellyfin-client`. What to look at: does the app start at the asked position, does Pause/Unpause/Seek move it, does
  `SetVolume` change its volume, and does its own next-episode autoplay fight Totum's queue.
- **The phone's picker line has not been looked at on a screen**: it is two strings and a `when` branch, covered by
  `PlayOnTest`/`TargetPlaysTest`, but no emulator run had a Pi to list a Jellyfin target.
- **Starting the app.** A closed Fire Stick app could be started over adb as Kodi is (ADR 177: `TOTUM_ADB_TARGETS`,
  `TOTUM_START_APPS=...=org.jellyfin.androidtv/<activity>`), which would turn "by hand" into "picking it starts it". Left out:
  the activity name is unverified (the stick's app version was read from the dot-files notes, 0.19.10, not from the stick).
  Ask Dewi whether he wants it.
- **A client that has no session at all** (the app never signed in, or Jellyfin forgot it after a day idle) is "not open",
  which is what it is; a configured name that matches nothing logs `no open Jellyfin app answers to <label>`.
- **Jellyfin's own watched state** is not written (ADR 154); a film played this way is marked watched by Jellyfin itself,
  since the app plays it.

## Runbook: deploy on the Pi (the orchestrator runs these; nothing here has been run)

1. Deploy the Totum release that contains this change, as usual: `cd ~/code/totum/server/totum-fetch && git pull &&
   TOTUM_VERSION=0.1.N docker compose up -d --build`. With `TOTUM_JELLYFIN_CLIENTS` unset the Pi behaves as before, and
   `GET /targets` gains a `plays` field on every target (`ANYTHING`).
2. Open the Jellyfin app on the Fire Stick and find its device name (it prints nothing secret; the key stays in the container):
   ```
   docker exec totum-fetch sh -c 'curl -s -H "Authorization: MediaBrowser Token=\"$TOTUM_JELLYFIN_KEY\"" "$TOTUM_JELLYFIN_URL/Sessions"' \
     | python3 -c 'import json,sys; [print(s["DeviceName"], "|", s["Client"], "| controllable:", s["SupportsMediaControl"]) for s in json.load(sys.stdin)]'
   ```
   The stick's line should say `controllable: True`. (On 2026-11-20 the only line was a phone, `controllable: False`.)
3. Name it in `/var/lib/totum-fetch/fetch.env` (root-owned, mode 600), one line, for example
   `TOTUM_JELLYFIN_CLIENTS=Fire Stick=<a part of its device name>` (`;` between several; an exact device id also works).
   Back the file up first: `sudo cp -p /var/lib/totum-fetch/fetch.env /var/lib/totum-fetch/fetch.env.pre-jellyfin-clients`.
4. Recreate the container so it reads the file: `cd ~/code/totum/server/totum-fetch && TOTUM_VERSION=<same> docker compose up -d`.
5. Check it, from the Pi (read only):
   ```
   curl -s 127.0.0.1:8772/targets | python3 -c 'import json,sys; [print(t["target"]["id"], t["target"]["kind"], "power", t["power"], "plays", t["plays"], "byHand", t["switchedOnByHand"]) for t in json.load(sys.stdin)["targets"] if t["target"]["id"].startswith("jellyfin-")]'
   docker logs totum-fetch 2>&1 | grep 'jellyfin-client' | tail
   ```
   Expect `jellyfin-fire-stick TV power ON plays JELLYFIN_ONLY byHand True` while the app is open and `power ASLEEP` after it is
   closed (within about ten seconds), and a log line `1 configured Jellyfin client(s): Fire Stick -> open=true`.
6. Try it from the phone: "Play on" the Fire Stick target, play a film from the Jellyfin page. Then pause, seek, volume and
   Stop from the Queue tab's chip. Try a YouTube video with it selected: it should play on the phone, and the log says why.
7. Roll back: delete the `TOTUM_JELLYFIN_CLIENTS` line (or restore `fetch.env.pre-jellyfin-clients`) and run step 4 again; the
   target disappears from the phone within a minute. Its saved queue stays in
   `/var/lib/totum-fetch/home/.totum/targets/target-jellyfin-fire-stick.json` and is picked up again if the line returns.
