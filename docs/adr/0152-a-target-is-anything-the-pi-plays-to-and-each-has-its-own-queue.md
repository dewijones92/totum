---
title: A target is anything the Pi plays to, and each has its own queue
kind: adr
status: accepted
updated: 2026-11-07
---

# 152. A target is anything the Pi plays to, and each has its own queue

- Status: Accepted
- Date: 2026-11-07
- Amended by: [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md) (the choice of device outlives the Queue tab)
- Amends: [ADR 116](0116-speaker-queues-play-on-the-pi.md) (a speaker becomes one kind of target; `/speakers` becomes
  `/targets`)

## Context

[The Pi plays everywhere](../todos/pi-plays-everywhere.md) (Dewi, 2026-11-06): the Pi does the work, the phone is a
remote, and the LG TV, the Chromecast Ultra, PC browsers and the Pi's Bluetooth speakers are all the same kind of thing
to send a queue to. ADR 116 had built that for speakers only, with its own wire types (`Speaker*`) and no phone side.

## Decision

- **One model, in `:core:data` (`data/targets`).** `Target(id, label, kind)` with `TargetKind` PHONE, SPEAKER, TV,
  CHROMECAST, BROWSER (`hasPicture` says whether the kind shows a picture). `TargetState` is ADR 116's speaker state plus
  `sound` (`SoundSettings`: skip silence, boost) and, on each entry, who added it and whether it reports to YouTube.
  `QueueMode` gained `PLAY` (insert after what plays and start the first inserted item, which is what the phone's
  "play" does to its own queue). The speaker types were renamed, not copied: nothing but the Pi used them.
- **One queue per target, one deck per queue on the Pi (`cli/serve/targets`).** `TargetDeck` is ADR 116's `SpeakerDeck`
  made kind-agnostic: it owns the `QueueSnapshot`, starts items, moves on at an end, saves itself (`DeckStore`) and
  restores paused. What differs per kind is a `TargetKit` (a `TargetPlayer` and a `TargetLink`) from a `TargetSource`:
  Bluetooth speakers (BlueZ + mpv, unchanged), Chromecast TVs (`cli/serve/cast`, [ADR 153](0153-the-pi-runs-the-phones-sound-processing.md); what a TV loads is the phone's own manifest and segments since [ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)).
  A deck asks for a picture only when its kind has one. Routes: `GET /targets`, `POST /targets/queue?id=`,
  `POST /targets/control?id=`, behind the same token as the rest.
- **The phone is a remote.** `TargetsClient` (on `HomePiCall`) and `TargetsRepository` in the app poll `GET /targets`
  every 2 s while the Queue tab is open. The Queue tab shows chips (📱 This phone, one per target that has a queue, ➕ for
  the idle ones); a remote chip shows that target's queue on the same `MediaItemRow`, with group headings, jump, remove,
  previous/play/next/stop, volume, the sound switches and a colour.
- **Where a play goes is decided in one place.** `PlaybackQueue.destination` (a `QueueDestination`) is asked first by
  `enqueue`, `playNext`, `enqueueAll`, `playNextAll`, `playNow` and `playAll`; while a remote chip is the one being
  looked at, `TargetsRepository.take` sends the items there and the phone's queue is untouched. Only deliberate taps
  (`byYou`) are redirected. "Add to group" is not redirected (a group's position means nothing to a remote queue).
  *Looking at* a target lasted only while the Queue tab was on screen; **superseded by [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md)**:
  the choice is the remembered last used device, kept when the Queue tab is left and across restarts. Plays that must be local
  say so (`local = true`, never redirected whatever is selected): Kid TV, a kid-locked home-screen shortcut and the
  Shorts reel (opening a reel is phone-only by nature; recorded as an exception in [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)).
  The redirect reports what the Pi really answered: the items it queued, not the items asked for. If the Pi refuses
  (a 422 when nothing in the request is playable), cannot be reached, or queues but cannot start, the phone plays the
  items itself and the strip says "⚠️ <target> didn't take it (…). Playing on this phone instead." (`PlaybackQueue.playHere`).
- **"Send to" moves what plays.** `SendTo.plan` takes the playing item and the rest of its group (not what came before),
  with the second it was at. It is an *insert*, mode `PLAY`: the items go in after what plays on the target and the
  first starts there, so the target's other items stay and a TV that fails to start loses nothing (it was `REPLACE` in
  the first build, which broke "never a whole-queue replace"). The Pi starts it at that item and second; the phone pauses and releases those entries only
  once the target answers that it is playing, so a failed send leaves the phone playing. It runs as a progress-strip
  operation (`SEND_TO_TARGET`).
- **Colours are automatic and editable.** `TargetColours.assign` gives the phone tangerine and every other target the
  first free colour of an eight-colour palette starting from a hash of its id; the result is stored by the phone, so a
  new target never recolours an old one, and an edited colour is respected and not given to anyone else.
- **Only the phone that signed the Pi in reports to YouTube.** The Pi plays with the account one phone handed over, so each
  queue request says who sent it (`Requester(id, label)`; the phone's label is its model and a four-character id
  suffix, so two of one model differ). The Pi records which requester id handed the sign-in over
  (`YouTubeOwnership`, `youtube-owner.json`) and that id is the owner: an entry carries `reportsToYouTube` only when its
  requester's id is the recorded one (the old `owner` flag a phone claimed for itself is gone), and
  `TargetReporting.videoIdToReport` is the one rule: reports only if the entry's requester is the owner, the Pi is
  signed in, and it is a YouTube video. `YouTubeProgress` reports on pause, skip, an item ending and every 30 s of
  playing, and logs why for every entry it does not report. Every entry shows "➕ Added by …".
  A hand-over from a different requester while one is held is refused with a 409 (logged on the Pi), and the phone
  that was refused says "🔒 The home server is signed in to YouTube from another phone (…); sign that one out first"
  as a notification and stops trying. Accounts cannot be told apart from the tokens, so the same Google account on a
  second phone is refused too. Signing out (`DELETE /account?by=<id>`) is refused for anyone but the recorded phone, and
  clearing it lets the next phone sign in. The first hand-over after this change on a Pi that was already signed in
  claims ownership (nothing was recorded before); the owner's phone does that at its next download of a video.
- **A play never changes a device's volume (amended 2026-11-07).** The first build gave every deck a volume of 70 and
  pushed it to the TV (`set_volume`) or to mpv on every play, so the first send to Dewi's LG, left at 1%, would have gone to
  70%. Now only a `VOLUME` action from the phone sets a volume: `PlayRequest` carries none, and `TargetState.volume` is
  nullable, read from the device (a Chromecast's receiver status through the helper's `volume` event, or the level the
  phone last chose on a speaker) and unknown, not 70, when the device has not said. The deck saves no volume and ignores
  the one an older deck file holds (`"volume": 70` still decodes and is never applied). On a speaker, mpv gets
  `--volume` only after a volume the phone chose, kept for that speaker until the Pi restarts; otherwise it plays untouched.
  The phone shows "🔈 Volume not known" until a level is known. A Chromecast `stop` is sent only when Totum has something
  loaded, since `pychromecast` answers a stop with no media session as a failure ("Failed to execute stop"), which the
  helper used to report as an error before every load.
- **A live item has no length (amended 2026-11-07).** The LG reported `durationMs=43199000` for the live jazz radio: the
  receiver's answer for a live DASH stream is YouTube's 12-hour DVR window, which the strip drew as a 12 h bar. The deck
  now says `live` in `TargetState` and sends no `durationMs` when the current item's kind is `LIVE` or the player found it
  live (`ChromecastPlayer` reads `LocalManifests.isLive` of what it was given); progress reported to YouTube carries no
  length either. The phone shows the app's own "🔴 LIVE" badge (`TagBadge`, `liveTagLabel`, as in the player and rows) in
  place of the bar and the clock, from `TargetState.isLive` (the field, or the item's kind, so an older Pi that sends
  only a length still reads as live), which is the rule the phone's own player already follows for a live item
  ([ADR 69](0069-live-streams-play-a-manifest-totum-builds.md): no length, "LIVE" in the bar).
- **STOP hands a TV back by quitting the app Totum launched (amended 2026-11-07).** After STOP the Pi logged "nothing to
  hand back, the app stays on the TV" and the Default Media Receiver stayed on the LG's screen. `CastLink.release` (a
  deck's STOP, or a queue reaching its end; never a move between items) now sends the helper `release`. The helper
  (`Handback`) records what the TV was running when Totum first loaded: nothing, or the idle backdrop app (`E8C28D3C`),
  means the receiver app is Totum's and `quit_app()` is called, but only if the TV still shows the Default Media
  Receiver (`CC1AD845`) at that moment; any other app running before (YouTube, say), a different app on screen now, a
  receiver that was already open before the helper's first load, or nothing loaded at all leaves the TV alone, as
  before. Every decision is a `note` or `handback` event that `PyCastControl` logs with its reason.
- **A TV that is off is listed, and a play wakes it (amended 2026-11-07).** Dewi: with the TV off its cast receiver is
  off too, so he had to wake it by hand. HDMI-CEC does not reliably wake the LG (1 in 5, measured for `firetv-ctl`);
  Wake-on-LAN does, in about 5 s, on the right input.
  - **Config.** `TOTUM_CAST_TARGETS` takes an optional MAC after an `@`: `LG TV=192.168.0.219@20:28:bc:6b:f7:f8;Living
    Room=192.168.0.13`. The old `name=address` form is unchanged. A MAC is validated (six hex pairs, `:` or `-`); a bad
    one is logged and ignored, and the target is still listed without a wake. Extra broadcast addresses can be given
    in `TOTUM_WAKE_BROADCAST` (comma-separated).
  - **Presence.** A target with a MAC probes its receiver port (TCP 8009, 1.5 s) every 5 s in the background and says it in
    `TargetState.power`: `ON`, `ASLEEP` or `WAKING` (absent from an older Pi's answer reads as `ON`). It is always listed;
    asleep it is not an error. The phone's chip wears 💤 (asleep) or ⏰ (waking), and the Remote queue header shows
    "💤 Off · tap to wake", a button that sends the new control `WAKE`.
  - **A play or queue request to a sleeping target wakes it first** (`CastLink.connect`, before the deck's lock is taken,
    so `GET /targets` keeps answering): `TvWake.ensureAwake` sends the magic packet to `255.255.255.255`, the target's own
    /24 broadcast (derived from an IPv4 address) and any configured ones, on UDP ports 9 and 7, again every 5 s, and
    polls port 8009 once a second for up to 25 s; one wake at a time per TV, so two phones send one. Once the port
    answers the helper's own connect gets one more try (20 s) before the play goes ahead. (2026-11-14: a broadcast
    from the container never left Docker, so the packet also goes unicast to the TV's own address, see
    [ADR 158](0158-targets-end-of-batch-fixes.md).) On timeout the deck's
    `problem` is "❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings", `playing` is false, and the phone
    plays the items itself as for any target that could not start. A TV already on gets no packet.
  - **The phone shows it.** While `power` is `WAKING` the repository puts "⏰ Waking LG TV…" on the progress strip
    (`OperationKind.WAKE_TARGET`, driven by the polled state, so whichever phone asked, and capped at 45 s if the
    polling stops), ending ✅ "LG TV is awake" or ❌ with the Pi's reason. The phone's POSTs to `/targets` have a 60 s
    read timeout (the rest of the app's is 20 s) so a wake does not look like a dead Pi.
  - **Not built / untested:** `totum-fetch` is on the `bin_private` bridge, so a broadcast from inside the container may
    not reach the LAN; the subnet broadcast is used for that reason. Not tested from the container (nor on the real TV
    from here): the coordinator tests it. If it does not reach, put `TOTUM_WAKE_BROADCAST` right or give the container
    host networking for the packet. Linked decks (already playing) skip the wake: a TV switched off mid-queue is not
    woken until the deck is stopped. (Amended by [ADR 158](0158-targets-end-of-batch-fixes.md): a lost connection now
    stops the deck, and Play wakes the TV and resumes at the saved place.) Speakers need nothing: Bluetooth already links on demand.
- **Every phone sees the same queues.** Any phone signed in to the home server reads and edits the targets' queues
  through the same operations; edits are small operations, never a whole-queue replace.

## Consequences

- Speakers are targets; the leftover `speaker-<id>.json` queue files are not read (the new files are
  `target-<id>.json`), so a speaker queue saved before this change starts empty once.
- Home Assistant's brown-noise and jazz switches (`hermes-audio-ctl`, in the Pi's `dot-files`) are still a second player
  on the same speakers; making them call `POST /targets/queue` is a dot-files change. The Totum side (repeat, files on
  the Pi, `problem`, 8770 on the loopback) is [ADR 155](0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md);
  the adapter is in `tools/handoff/hermes-audio/`, to be deployed by hand.
- The Pi cannot tell phones apart beyond the id a phone sends. Anyone with a home-server token can send another
  phone's id; this is a household, not a threat model, and the rule is written down so it is not mistaken for one.
- Connecting to a target (a Chromecast helper, a Bluetooth link) happens before the deck's lock is taken, so a slow or
  absent TV no longer blocks the deck for the 20 s the phone waits; the wait is capped at 10 s, and an unknown target id
  answers 404 without scanning every source.
- The target poll runs only while the Queue tab is on screen and the app started, and logs a failed read once and then
  every 30th, a successful one only when the queues changed.
- A remote queue's rows offer "Play next" (moves the item to just after what plays there); "Add to queue" and "Peek"
  are not offered on a remote row (see [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)).
- Not built: PC browsers as targets (the `BROWSER` kind exists, nothing registers one), "Play on…" as a long press on
  every play button, and the mini-player pill and notification per target.
