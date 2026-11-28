---
title: The treat button puts a chosen film or playlist on a TV for a while, and the Pi ends it
kind: adr
status: accepted
updated: 2026-11-27
---

# 196. The treat button puts a chosen film or playlist on a TV for a while, and the Pi ends it

- Status: Accepted
- Date: 2026-11-27

## Context

Dewi, 2026-11-27: "Toy Story on the TV for 15 minutes, then off", one tap, with the choices configurable. It builds
on three things that already exist: Kid TV ([ADR 148](0148-kid-tv-is-kid-mode-on-the-tv-and-a-pinned-playlist.md)), which
casts a pinned playlist to a TV from the phone; the sleep timer and its fade on the Pi's targets
([ADR 166](0166-the-sleep-timer-fades-by-how-loud-it-sounds-on-the-phone-and-on-a-target.md), `DeckSleep`); and device
power ([ADR 177](0177-a-device-says-how-it-comes-on-and-goes-off-lg-over-webos-kodi-started-over-adb.md)), which can switch
the LG off over webOS. The point of the feature is that it ends by itself, so the ending cannot depend on the phone.

## Decision

- **The TV is a Pi target, and the Pi ends the treat.** The button sends the content to the chosen target
  (`TargetsRepository.playOnly`, a `REPLACE`, never falling back to the phone: a treat that cannot reach the TV says
  so, it does not play a film on the phone) and then arms the target's own timer (`TargetAction.SLEEP`,
  `AFTER_TIME`). The timer runs in `DeckSleep` on the Pi, so a locked phone, a killed app or a dead battery cannot leave
  the TV running. Kid TV's phone-cast route is unchanged and remains the way to use a TV the Pi does not know.
- **What plays is a pin the app can send whole:** a pinned playlist (not a music one) or a pinned single item (a film
  from Jellyfin, a video). A pin is the existing way to name something in this app, and the kinds that need the phone to
  resolve them (radio, shuffle-all) are not offered.
- **Saved defaults and a one-off.** `TreatChoice` holds the TV, the pin, the minutes (5 to 90 in fives, default 15),
  the end and whether to fade (default on, the Bedtime page's fade length). It lives in its own preferences file and the
  Drive backup (`treat.v1`). Tapping the Library tile starts with the defaults at once; a long press or the gear opens a
  sheet whose changes apply to "Start now" for that once, and are kept only by "Save as default".
- **Three ways to end** (`TreatEnd`): switch the TV off (the default; pause, restore the volume, then `switchOff`), pause,
  or stop (pause, restore the volume, stop and hand the target back). Stop is a new flag on the ask
  (`TargetSleepAsk.stop`, default off, so an older phone or Pi is unaffected) with a matching `DeckSleep` step.
- **A TV that cannot be switched off falls back to stop**, decided from the state the Pi reports (`canSwitchOff`)
  when the treat starts, so the Pi is only ever asked for what it can do. It is said once, in the toast that comes with
  the treat starting and in the status line, and logged with the inputs.
- **Fail closed.** If the timer cannot be set after the TV has started playing, the TV is stopped and the failure says so:
  playing with no end is the one outcome this feature must not have. If the Pi refuses the play, nothing is stopped on
  it (what is playing there is somebody else's).
- **While it runs:** a progress-strip entry ("🧸 Toy Story on LG TV", "⏳ 12 min left, then it switches off", **Stop**;
  `OperationKind.TREAT` and a free-text `Operation.detail`, which any operation can now set) and a silent notification
  that counts down by itself (`setChronometerCountDown`, timed out a minute after the end), so it cannot go stale if the
  app is killed. Stop (strip or tile) cancels the timer and stops the TV; it does not switch it off, because that is
  the timer's job. If the treat's timer has gone from the Pi early (the content ended and the deck released it),
  the strip entry ends instead of counting down time that is not running.
- **It survives the app closing.** The running treat is saved (`TreatRunning`); at the next start the app watches the
  Pi's timer again (`Treat.resume`, called with the other start-up work) or clears it if its time has passed.
- **One more way in:** an app-icon shortcut (`com.dewijones92.totum.TREAT`, handled in `MainActivity` like Ask) taps the
  button; not set up, it says so.

## Consequences

- A film shorter than the time still ends with the chosen end action. The treat's timer carries a flag
  (`TargetSleepAsk.treat`, set only by the treat button); when the last item ends before the countdown does, the deck
  takes that timer (`DeckSleep.takeTreat`, which also puts a faded volume back), hands the target back and, when the end is
  "switch off" and the TV can be switched off, switches it off. Hand-set sleep timers have no flag and keep ADR 166's
  behaviour: they end with the playback they were set for. An earlier item ending changes nothing, and a failed last item
  counts as an end. "Pause" has nothing left to pause at that point, so it only hands the target back, as stop does.
- The LG must be paired for webOS (`totum webos-pair`, the orchestrator's job) for "switch off" to be possible;
  until then the button falls back to stop and says so.
- Only TVs and Chromecasts the Pi lists are offered. Away from home the Pi is not reachable and the button says so.
- Not run against a real TV in this change.

## Tests

`TreatRulesTest` (defaults, the plan for the defaults and for an override, the fall back, clamping, the codec, which pins
and targets are offered), `TreatTest` (a tap with nothing chosen, a tap with the defaults and the exact timer request,
a one-off that leaves the defaults alone, saved defaults, the fall back and its status, a timer that cannot be set, a TV
that refuses, a TV the Pi does not list, the strip entry and Stop, stopping from the tile, time up, an early end, a
restart), `TargetDeckStopTimerTest` (the Pi's stop step: pause, stop, hand back; and that without the flag it only
pauses), `TargetDeckTreatEndsEarlyTest` (a film that ends before the timer: switch off, stop, a TV that cannot be switched
off, the first of two parts ending, and a hand-set timer that must not switch off).
