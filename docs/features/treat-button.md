---
title: The treat button, a film or playlist on the TV for a while, then off
kind: feature
area: casting/pi
status: built 2026-11-27, Batch C task V (unit tests; not run on a real TV)
updated: 2026-11-27
---

# 🧸 The treat button

Decision: [ADR 196](../adr/0196-the-treat-button-puts-a-film-on-the-tv-for-a-while-and-the-pi-ends-it.md). Builds on
[Kid TV](kid-tv-button.md), the sleep timer on a target ([bedtime-sleep-timer](bedtime-sleep-timer.md),
[ADR 166](../adr/0166-the-sleep-timer-fades-by-how-loud-it-sounds-on-the-phone-and-on-a-target.md)) and device power
([pi-plays-everywhere](pi-plays-everywhere.md#power-how-a-device-comes-on-and-goes-off-adr-177)). Backlog item:
[todos/treat-button](../todos/treat-button.md).

## What a person sees

- **Library → the 🧸 Treat tile** (under Kid TV). Set up, it reads "▶️ Toy Story on LG TV · 15 min, then it switches off";
  one tap starts it with the saved defaults. While it runs it reads "✅ Toy Story on LG TV · 12 min left · tap to stop"; a
  tap stops it. Not set up, a tap opens the settings. The gear, or a long press, opens them whatever the state.
- **The sheet:** 📺 TV (the TVs and Chromecasts the home server lists; a remembered one stays as "not reachable now"),
  🎞️ What to play (a pinned playlist or a pinned single item, such as a Jellyfin film), ⏱️ How long (a slider, 5 to 90
  minutes in fives, default 15), 🔚 Then (📴 switch the TV off, ⏸️ pause, ⏹️ stop), 🔉 fade the sound out, and two buttons:
  **▶️ Start now** uses what is on the sheet this once and leaves the saved defaults alone; **💾 Save as default** keeps it for
  the tile.
- **A TV that cannot be switched off** (not paired for webOS, or a Chromecast with no power control) is said on the sheet,
  and the treat stops it instead; the toast when it starts says so once.
- **While it runs:** a progress-strip entry, "🧸 Toy Story on LG TV" with "⏳ 12 min left, then it switches off" and **Stop**, and
  a silent notification that counts down by itself. Stop cancels the timer and stops the TV.
- **A film shorter than the time** still ends with the chosen end: when its last item ends the home server hands the TV
  back and, if the end is "switch off", switches it off (hand-set sleep timers are not touched; they end with the playback).
- **The app-icon shortcut "🧸 Treat on the TV"** does what a tap on the tile does.
- **Locking the phone or closing the app changes nothing:** the timer runs on the home server. Reopening the app finds the
  treat again and shows it.
- Failures are said in the tile and a toast: ❌ no longer pinned, ❌ the TV isn't listed (with what is), ❌ the TV wouldn't play
  it, ❌ nothing to play, ❌ the timer couldn't be set so it was stopped.

## Seams

`treat/TreatChoice` (the saved defaults, the running treat, their stores and the Drive backup key `treat.v1`),
`treat/TreatRules` (the plan: the timer request and the fall back, which pins and targets are offered), `treat/Treat` (the
orchestrator with ports, like `KidTv`), `treat/TreatWiring` (the ports from the container, the notification),
`treat/TreatRequests` (the shortcut), `ui/library/TreatTile`, `ui/treat/TreatSheet` and `TreatText`. On the home server:
`TargetSleepAsk.stop` and `DeckSleep`'s stop step. On the phone: `TargetsRepository.playOnly` (a play with no fall back to the
phone), `awaitStart` and `endPlayback`; `OperationKind.TREAT` and `Operation.detail`.

## Diagnostics

`treat` lines: `treat loaded: …`, `treat start from <why>: "<title>" on <TV> for N min, end=… fade=… (this time only | the saved
defaults)`, `treat plan for …: … end asked=… effective=… fellBack=… canSwitchOff=… ask=…`, `treat is on: … until <ms>`, `treat
"<title>" on <TV>: N min left`, `treat refused the play, nothing is stopped on it`, `treat stopped <id> (<why>)`,
`treat defaults saved from …`, `treat notification posted/cleared`. The home server logs `sleep timer armed on …` and `sleep
timer on … fires`. The report's state block carries `treat.pin`, `treat.tv`, `treat.minutes`, `treat.end`, `treat.fade`,
`treat.status`.

## Not done / needs a TV

- Not run on a real TV. First check, daytime: Toy Story pinned from Jellyfin on the LG for 5 minutes; fade, pause, switch off,
  and the strip and notification; then again with the phone locked and the app swiped away.
- Only TVs the home server lists. Kid TV's phone-cast route remains for the rest.
- Android does not let an app keep the strip alive after it is killed; the timer and the notification's countdown do carry on.

## Seen on the emulator (27 Nov 2026)

On `totum-api35` with a seeded pinned playlist and a saved choice (LG TV, 15 min, switch off, fade), at 1080x2400 and at
320x640@160: the Library tile reads "▶️ Toy Story on LG TV · 15 min, then it switches off", and the sheet lists the real
targets the home server answered (LG TV, Living Room, Kodi), the pins, the slider at 15 min, the three ends, the fade switch
and the two buttons; at 320x640 the sheet scrolls and nothing is cut off. Screenshots are scratch, not kept in the repo
(`treat-tile-big.png`, `treat-tile-small.png`, `treat-sheet-big.png`, `treat-sheet-small.png` in the builder's scratchpad
`shots/` folder). Start now, Stop and the countdown were not driven: they need a TV.

## Tests

`TreatRulesTest`, `TreatTest` (`:app`), `TargetDeckStopTimerTest` (`:cli`). See [ADR 196](../adr/0196-the-treat-button-puts-a-film-on-the-tv-for-a-while-and-the-pi-ends-it.md).
