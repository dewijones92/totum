---
title: Play on… is one choice that remembers the last device, offered everywhere, with home devices always listed
kind: adr
status: accepted
updated: 2026-11-13
---

# 169. Play on… is one choice that remembers the last device, offered everywhere, with home devices always listed

- Status: Accepted
- Date: 2026-11-13
- Amends: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (the choice of device no
  longer ends with the Queue tab; "looking at a target" and "where plays go" become one remembered choice)

## Context

Dewi, 2026-11-13: *"Device picker on every play/queue action, one-tap default to the current device; each device a
queue with its own colour, shown on playing items everywhere"*, and *"when I'm home, I want my home devices to be
listed, even if they're off, and I want our solution to switch them on so it's all convenient."*

ADR 152 already gave each target a queue, a colour and a chip row, and sent Play, Add, Play next and Shuffle all to
whichever chip was being looked at. Two things made it unreachable in practice: the choice lived only on the Queue tab
(leaving it put every play back on the phone), and nothing outside the Queue tab showed or changed it. The Pi already
lists every configured TV, Chromecast and paired Bluetooth speaker whether it is on or not, and wakes one on a play
(ADR 152: Wake-on-LAN for the LG, a Bluetooth link for speakers, the TV powering itself for a Chromecast).

## Decision

- **One remembered choice: the last used device.** `PlayOn` (`:core:data/targets`) is the pure rule. `TargetsRepository`
  keeps the choice, saved in `PlayOnStore` (`totum_play_on` prefs) and so kept across restarts. It is not cleared by
  leaving the Queue tab: `queueTabLeft` is gone. Every play goes to it through the same seam as before
  (`PlaybackQueue.destination`; Play, Add, Play next, Shuffle all, play all, from rows, the player, pins, links, search
  and notifications), so no entry point needed a change and none can differ. Tapping a Queue chip and picking in the new
  picker are the same choice; a remote queue chip is simply the view of it.
- **At home all devices are offered, on or off; away, only this phone.** `PlayOn.options(listed, atHome)` is this phone
  first, then every device the Pi lists (an `ASLEEP` one included, `power` carried), or only the kinds marked
  `reachableAway` when not at home (`TargetKind`: PHONE and BROWSER; the Browser target another builder adds slots in
  there with no further change). **At home** is `PlayOn.atHome(homeServerAnswered, onUnmeteredNetwork)`: the home server
  answered the last read (a failed read keeps it for one more try, two in a row end it) and the connection is not metered.
  The Pi is reachable from anywhere through its public name, so reachability alone cannot say "home"; an unmetered
  connection is the stand-in for the home Wi-Fi. **This cannot tell home Wi-Fi from any other Wi-Fi** (a café, work): there
  the home devices would be offered, and a play would work (the Pi does the casting) but be odd. A tighter check (the
  LAN address, or a Wi-Fi name) needs Dewi's decision and is the one open question.
- **A choice that is not on offer is not a destination.** If the last used device is not listed or not at home, the
  destination is this phone and the log line says why (`PlayOn.why`: "last used LG TV, but not at home, so This phone").
  The remembered id is kept, so coming home brings the default back without a tap.
- **The picker is compact and defaults to the last device, so a play costs no extra tap.** A slim row under the header
  on every tab ("▶️ Play on [📺 LG TV ▾]", the device's colour on its border; `PlayOnRow`, in `ShellBanners`), drawn only
  while more than this phone is on offer; a tap opens the list (a dot in each device's colour, 💤 on a switched-off one),
  one more picks. Every item menu has "📡 Play on…" (the shared `ActionSheet`, so rows, the queue and the full player all
  have it): it expands the same list and plays that item on the pick, making it the new last used device. Away from home
  the row and the menu entry are not drawn. A bulk action (selection menus, group menus) uses the remembered device.
- **Picking a switched-off device wakes it at once** (`select(wake = true)` sends the existing `WAKE` control, which on the
  Pi is Wake-on-LAN for a TV with a MAC); picking in the Queue chip row only looks and does not wake. A play to a sleeping
  device still wakes it first (ADR 152), so tapping Play with the TV remembered and off works without picking again, with
  the "⏰ Waking LG TV…" strip. Bluetooth speakers are linked by the Pi when played to; a pick does not link them, because a
  link made for nothing is dropped again. A speaker the Pi cannot reach and that is switched on by hand
  (`TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND`, the dprofy Soundmic) answers "🔌 Switch Soundmic on, then try again" (was
  "📶 …"), in the phone's fallback notice ("⚠️ Soundmic didn't take it (…). Playing on this phone instead.").
- **The device's colour is on the item wherever it is playing.** `DeviceMarks` maps an item id to the device playing it
  (from the polled `TargetState`s: the current entry of a device that is playing) or, failing that, to this phone for the
  phone's current item, with the device's colour from `TargetColours`. `DeviceMarkBadge` ("📺 LG TV", coloured border and
  fill) shows on every `MediaItemRow` (so every list, the queue and a remote queue), the mini player and the full
  player's title block. Nothing is marked when only this phone is on offer. The state is polled every 10 s on every tab
  (every 2 s on the Queue tab as before), so rows can show it.
- **Not built, and why.** The phone's media notification stays the phone's (it is the phone's own media session; a
  per-device notification or pill is the mini-player-per-target item ADR 152 left open). The mini player and full player
  show the phone's item, not a remote device's; a remote item's colour is on its row and in the Queue tab. **Peek**
  (play without touching the queue, opening the phone's player) stays on the phone, like the Shorts reel
  ([ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)).

## Consequences

- With a device remembered, every play from every screen goes there until another is picked, which is the point and also
  a change: before, leaving the Queue tab returned plays to the phone. The row under the header is how it stays visible.
- The Pi's target poll now runs on every tab while the app is on screen and a home server is set up (one small GET every
  10 s), and logs a changed list once, as before.
- `Target.chipLabel` is the one place a device's name and power are worded, now used by the Queue chips, the picker and
  the item menu.
- Not run on a real TV, soundbar or the Soundmic from here: the orchestrator's real-device pass.
