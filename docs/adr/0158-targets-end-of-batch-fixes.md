---
title: Targets, end-of-batch fixes - loops, ids, a loopback listener, slow answers, a TV that is switched off, resume, structured problems
kind: adr
status: accepted
updated: 2026-11-13
---

# 158. Targets, end-of-batch fixes

- Status: Accepted
- Date: 2026-11-09 (sections from "A slow answer" down added 2026-11-13)
- Amends: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md),
  [ADR 155](0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)

## Context

An Opus review and two sweeps of the Pi-targets batch (2026-11-07) found a handful of defects, each checked against the
code before it was fixed. The decisions were the coordinator's (Dewi delegated them). They are recorded here, one section
per finding.

## Decisions

### A loopback listener, not all of 8770 (finding 2)

ADR 155 published 8770 on the Pi's loopback so Home Assistant could call `/targets`. 8770 serves every route with no
token of its own (nginx's `auth_request` is the only guard): `/jobs`, `/files`, `/account`, `/ask`, `/jellyfin` as well.
`totum serve` now starts a **second listener** (`TOTUM_LOOPBACK_PORT`, default 8772) that answers `GET /health` and the
`/targets` routes and nothing else (404 and a warning in the log for anything else). Compose publishes
`127.0.0.1:8772:8772` and no longer publishes 8770; the container's own health check still uses 8770 inside the
container. The adapter's `TOTUM_URL` default is `http://127.0.0.1:8772`. Other containers on `bin_private` can reach 8772
as they could always reach 8770; the listener narrows what the host can do, not what the network can.
Tests: `LoopbackListenerTest`.

### Looping belongs to an entry, repeat to the deck (finding 1)

Home Assistant's brown noise sent `repeat: ONE` for the whole deck. Nothing reset it and the phone could neither see
nor change it, so the next podcast sent to that speaker looped its first item for ever. Now:

- `TargetQueueRequest.loop` marks the items of that request, and `TargetEntry.loop` carries it back in `GET /targets`.
  An entry that loops starts again when it ends, whatever the deck's repeat, with the same two guards as repeat (never
  after a failure, never after an item that ended within 2 s). It is remembered per item, saved with the deck, and
  replaced by the next request that names the item.
- The deck's `repeat` stays the user's choice (off, this one, the queue: `RepeatMode.onATarget`), set only by the
  `REPEAT` control or a request's `repeat`, and works as the phone's queue repeat does.
- The phone's Remote queue shows the deck's repeat with the player's own `RepeatTile` (the same picker, compact),
  limited to the modes a target honours, and "🔂 Loops" on an entry that loops.
- `tools/handoff/hermes-audio/audio-ctl.py` queues brown noise with `loop: true` and never sends `repeat`.
  It supersedes the stopgap on the deployed adapter (a REPEAT OFF on stop). A deck that was left on `ONE` by the old
  adapter keeps it until someone changes it, which the phone can now do.
- Tests: `TargetDeckTest` (loops, no inheritance by the next request, survives a restart, never repeats a failure,
  leaves the deck's repeat alone), `TargetRepeatTest`, `test_audio_ctl.py`.

### A slow answer is not a refusal: the Pi accepts, then starts (finding 3)

`POST /targets/queue` used to connect to the target, look the item up and start it before answering, so a TV that
needed waking, a speaker that took 15 s to link or a YouTube lookup made the phone's 20 s read time out. `HomePiCall`
read a timeout as "no", the phone played the item itself, and the Pi started it as well.

- **The Pi accepts at once** (`TargetDeck.accept`: validate, change the queue, mark `starting`) and does the slow part in
  the background (`startPending`: connect, look up, start). The route waits at most 1.5 s for the start, so a speaker
  that is already linked answers with its final state as before; anything slower answers with `starting: true` and
  `playing: false`. `GET /targets` carries `starting` until the start has ended, then `playing` or the problem.
- **The phone watches** (`TargetsRepository.settle`): while the answer says `starting` it reads the Pi every half second
  (the progress strip's "Sending to X" row says "⏳ Starting on X…"; a TV that is waking also has its own "⏰ Waking X…"
  row from the polled `power`), for at most 2 minutes, giving up after 3 failed reads in a row.
- **A timeout, a dropped connection or a 5xx is not a refusal.** The phone reads `GET /targets` before it decides: if the
  Pi holds the items and is starting or playing them, it keeps watching; if it does not hold them, the phone plays them
  itself. A 4xx (a 422 "nothing playable") is a clear refusal and is played here at once.
- **When the phone takes over, the items come off the Pi.** `TargetControl.itemIds` with `REMOVE` takes the sent items
  off the target by id (a phone that fell back after the Pi had queued them, or could not tell).
- The hermes-audio adapter waits while `starting` is set, then prunes by where the queue ended up.
- Tests: `TargetDeckStartTest`, `TargetRoutesSlowStartTest` (cli); `TargetsRepositoryTest`; `test_audio_ctl.py`.

### Reach before resolve (finding 13)

Report 20261107T105330: a send to the soundbar failed Bluetooth, the Pi then looked the stream up anyway, the phone had
timed out and said "home server didn't answer". `TargetDeck.startAt` now checks that the target is reachable **before**
looking an item up: an unreachable target holds at the item and says why without a lookup. The Pi logs the phases of
each start (`link=… sinkWait=… resolve=… total=…`, the route logs its own `answered after …ms`) and the phone logs every
control it sends and the answer.

### A TV switched off mid-item (finding 4) and a stall

`chromecast_helper.py` registers a pychromecast connection listener and says `connection` events. `LOST`,
`DISCONNECTED` and `FAILED` drop the helper (the next reach starts a fresh one) and tell the deck (`TargetPlayer.onLost`):
the deck stops, keeps its place, shows power `ASLEEP` at once (the port probe confirms) and the problem
`SWITCHED_OFF` ("📺 LG TV was switched off"). Play wakes it (Wake-on-LAN) and resumes at the saved place. A deck that is
playing and whose position has not moved for 20 s (checked every 5 s, never while paused or starting) gets a `STALLED`
problem, which clears when it moves again. A TV that stays in `PLAYING` with a hung picture and a position that still
advances is not detected; a buffering TV (position frozen) is.

### Resume from the saved place (finding 5)

The deck keeps the place of the current item (captured on pause, stop, a lost connection and every 30 s while playing,
and saved in the deck file). STOP then Play, and a Pi restart then Play, start the item there. Talk, music and files
resume; a live item starts at the edge; moving to another item, an item that ended, and the end of the queue start at 0.

### Edits by item id (finding 6)

`TargetControl.itemId` (JUMP, REMOVE) and `itemIds` (REMOVE) name what the phone meant. The Pi acts on the entry with that
id (the nearest one to the asked index if the id is in the queue twice), or answers **409** with the queue as it is, and
the phone reads again. The phone and `audio-ctl.py` send ids.

### A structured problem (finding 7) and its words (finding 8)

`TargetState.trouble` is `TargetTrouble(kind, itemId, reason, untilMs)`; `problem` stays the plain text for the adapter's
`last_error`. Kinds: `NOT_CONNECTED`, `SWITCH_ON`, `SWITCHED_OFF`, `WONT_WAKE`, `COULD_NOT_PLAY`, `YOUTUBE_BACKOFF`,
`STALLED`. The phone composes the line from its own string resources (`TargetWords`, `ContextTargetWords`), with the
title through the spoiler shield (ADR 141) and the reason words in the frame of ADR 157's "couldn't play":

- a speaker out of range: "📶 Couldn't reach <name>: it isn't connected (off or out of range)";
- a speaker that has to be switched on by hand (`TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND=Soundmic,…`, names or ids): "📶 Switch
  <name> on, then try again". **The Pi cannot tell a switched-off speaker from one out of range** (BlueZ answers the same),
  so which speakers are switched on by hand is configuration; this is the coordinator's call and is unverified on the
  real Soundmic;
- a 401, from a read or an action: "🔑 Sign in to the home server again". `TargetsRepository.problem`, which nothing
  collected, is now shown in the remote header, and a failed action stays until the next good action (a good read no
  longer wipes it).

### 💤 is asleep only (finding 9), live during a back-off (finding 10)

The idle line is "📭 Nothing playing on <name>"; 💤 is the TV's `ASLEEP` chip and "Off · tap to wake". Live on a speaker
while anonymous YouTube is backing off after a bot check (`BotBackOff.until`) has no stream, and the problem is
`YOUTUBE_BACKOFF` with the end of the back-off: "⏳ YouTube is refusing the Pi for now; try again around HH:MM" (the
phone formats the time).

### Small fixes (finding 11)

`SoundFiles` accepts only audio extensions (mp3, m4a, aac, ogg, oga, opus, flac, wav); `TargetDecks.deckFor` uses
`computeIfAbsent` (concurrent asks made one kit each); `ChromecastPlayer.nudgedAtTheEnd` re-checks the loaded item inside
the lock the player's `play` and `stop` take, so a cut-end nudge no longer stops an item loaded a moment before; the
helper's hand-back is remembered in `targets/handback-<id>.json` (a restart no longer leaves the Default Media Receiver
open on the TV).

### The remote panel seeks (finding 12)

The remote header has a seek slider (sends `SEEK` on release) with 10 s back and 30 s forward, as the phone's player has.
Tapping the row that is playing no longer jumps to it (which restarted it at 0): it does nothing, or plays when the deck
is paused. Tapping any other row jumps to it.

### Deploy

`fetch.env` may gain `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND`; compose and the image need nothing else (the hand-back file
lives in the existing `home/.totum/targets/`). Rebuild `totum-fetch` from the release; deploy the adapter
(`tools/handoff/hermes-audio/audio-ctl.py`) by hand. Old phones and old Pis still work together: unknown fields are
ignored both ways, an old Pi answers synchronously, and an old phone reads `problem`.
