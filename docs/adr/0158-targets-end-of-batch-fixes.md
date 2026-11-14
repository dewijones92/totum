---
title: Targets, end-of-batch fixes - loops, ids, a loopback listener, slow answers, a TV that is switched off, resume, structured problems
kind: adr
status: accepted
updated: 2026-11-14
---

# 158. Targets, end-of-batch fixes

- Status: Accepted
- Date: 2026-11-09 (sections from "A slow answer" down added 2026-11-13; the old-phone correction and "Second review" 2026-11-14)
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
  the background (`startPending`: connect, look up, start). **For a phone that says it understands that**
  (`TargetQueueRequest.understandsStarting`, sent by `TargetsRepository.queue` on every request since 2026-11-14) the route
  waits at most 1.5 s for the start, so a speaker that is already linked answers with its final state as before; anything
  slower answers with `starting: true` and `playing: false`. **A request without the field, which is every phone up to
  v0.1.911, is answered as before 28c78ada**: the route waits for the start (at most 55 s, under that phone's own 60 s read
  timeout), so the answer says `playing`. Without this a new Pi answered such a phone `starting: true, playing: false` after
  1.5 s, the phone read that as "it did not start", played the item itself and left the Pi playing it too. `GET /targets` carries `starting` until the start has ended, then `playing` or the problem.
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
(`tools/handoff/hermes-audio/audio-ctl.py`) by hand. Old phones and old Pis work together only because of
the `understandsStarting` field: unknown fields are ignored both ways, an old Pi answers synchronously (so a new phone
meets `starting: false`), an old phone reads `problem`, and a new Pi gives an old phone the synchronous answer it expects
(it said "old phones and old Pis still work together" before, which was false for the pair new Pi plus old phone: the item
played twice; found by the end-of-batch review of 2026-11-14 and covered by `TargetRoutesSlowStartTest`). Test of the
pair: a v0.1.911-shaped request, one with no `understandsStarting`, against a deck whose link connects after 2.5 s.

## Second review (2026-11-14)

An Opus review and a sweep of the targets and the speaker volume found seven more defects, each checked against the code
before it was fixed (the decisions were the coordinator's). Each one has a test seen failing first.

### A waiting start is called off by every edit

`accept` sets `pending` and `startPending` then links, which can take up to 25 s on a TV wake or 15 s on a Bluetooth
sink, **outside the deck's lock**. Only the start itself cleared `pending`, so a Stop, a Pause, a removal, a jump or a
lost connection during the wait changed nothing, and the item played as soon as the link arrived, sometimes the wrong
one (the pending start held an index that a removal had shifted).

- A pending start names its item (`PendingStart(itemId, index, startAtMs)`) and is found again by id when it runs; the
  index is only a hint (the nearest entry with that id wins). An item that has left the queue means nothing plays.
- Stop (`finish`), Pause (`pausePlaying`, so a sleep timer's pause too), a jump or skip (`moveTo`), a lost connection and a
  removal of the waiting item all call it off at once (`starting` goes false at once, not when the link arrives). A Play
  while a start is waiting does nothing: that start plays it.
- `startPending` remembers which start it was waiting for and plays only if that is still the waiting one. A start called
  off after the link came up hands the link back (`TargetLink.release`) unless another start is waiting, so a speaker
  that linked for nothing is not left connected.
- The phone's fall-back "withdraw" (`TargetsRepository.removeSent`) sends `TargetControl.stopIfCurrent`. A removal of the
  item that is playing normally moves on to the next one (that is what the queue's own remove means), but a withdrawn
  item must not send the Pi on to whatever was queued before it: with `stopIfCurrent` the deck stops, keeps nothing and
  hands the target back. An older Pi ignores the field (it moves on as before); a newer Pi with an older phone is
  unchanged. Deploy order: any.
- Tests: `TargetDeckStartTest` (a link held on a `CompletableDeferred`, then stop, pause, remove the item, remove another
  item, lost connection, and a withdrawn current item), `TargetsRepositoryTest` (the withdraw carries the flag).

### A speaker that goes away stops the queue

`MpvPlayer` has no `onLost`. When the Bluetooth sink vanished (Soundmic switched off mid-queue), mpv's exit went to
`ended`, which started the next item, resolved it, spawned mpv again and failed, in turn through the whole queue, ending
with a misleading "could not play".

- `TargetLink.present()` (default true; `BluezLink` asks PulseAudio whether the sink is there) is asked **outside the
  lock** each time an item ends. If it says no, the deck treats it as a lost connection: it stops at the item it was on,
  keeps its place, and says "🔌 <speaker> was switched off or went out of range" (`SWITCHED_OFF`). The Pi's own text is
  worded by the target's kind (a TV still says "📺 <name> was switched off"); the phone composes its line from the kind
  (`ContextTargetWords`, `TargetWords.trouble(…, kind)`), so a speaker gets the new words there too.
- Tests: `TargetDeckStartTest` (a link whose sink is gone: one play, no lookups for the rest, the place kept, the words),
  `TargetsRepositoryTest` (the words are told the kind); `TargetWordsTest` (instrumented, not run here).
- Not seen on the real Soundmic: whether mpv exits or goes on playing to nothing when the sink disappears. The exit path
  is covered; a player that stays alive is not detected (the 20 s stall check still reports it).

### The deck's lock is not held for the speaker's slow calls

A volume change from the phone ran `busctl tree`, `set-property` and `get-property` (up to three 25 s shell timeouts)
under the deck's lock, and the 30 s tick and the read after a link came up did the same, so a slow speaker stalled
Pause, Stop and the phone's reads.

- `VOLUME` is handled before the lock (`volumeChanged`), and the level reads (`refresh` in `tick` and after a link) run
  before taking it. `SpeakerVolume` serialises its own writes and reads with one mutex, in arrival order, so two volume
  changes still land in the order they were sent.
- Tests: `TargetDeckVolumeSafetyTest` (a volume write and a level read held open, a Pause still answers).

### Wake-on-LAN also goes straight to the TV

Proved on the real Pi on 2026-11-14: a magic packet sent from the `bin_private` container, to `255.255.255.255` and to
the TV's /24 broadcast, never left Docker; the same packet sent from the Pi host woke the LG in under 4 s. `TvWake` now
also sends the packet **unicast to the TV's own address on port 9** every round, and logs each address it tried and which
went out. The broadcasts are kept (they work from a host with a flat network). A unicast to a sleeping TV needs the Pi
host to know the TV's MAC, because a sleeping TV does not answer ARP: add a static entry on the Pi host,
`ip neigh replace <tv ip> lladdr <mac> nud permanent dev eth0`, which `server/README.md` documents. Tests: `TvWakeTest`.
Not seen on the real TV from this change; the coordinator adds the ARP entry and tests it.
