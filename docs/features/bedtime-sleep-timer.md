---
title: Bedtime sleep timer — the sleep timer arms itself at night, and every sleep timer fades out
kind: feature
area: player
status: built 2026-11-06, Batch A (tests owed) — ships switched off; the fade is on (20 s) for every sleep timer
updated: 2026-11-06
---

# 🌙 Bedtime sleep timer

**Ask (Dewi, 2026-11-05):** after 22:00 the sleep timer stops at the end of the current item and fades out; the times
configurable. Decision: [ADR 133](../adr/0133-bedtime-arms-the-sleep-timer-and-every-sleep-timer-fades.md); backlog:
[todos/bedtime-sleep-timer](../todos/bedtime-sleep-timer.md).

## What it does

- **At bedtime the ordinary sleep timer arms itself.** When something starts playing during bedtime (or bedtime begins
  while something plays), Totum sets the player's "after this item" sleep timer, so the moon button shows it, the
  quiet-modes chip shows it, and the queue stops at the item's end as it already does for a timer you set by hand.
  Nothing arms it when a sleep timer is already set, when the kid lock is on, or for a live stream (it never ends).
- **It fades out.** Over the last stretch of the last item, or of a countdown, the player's volume ramps to silence, then
  playback stops; the volume is back to full the moment the timer ends or is cancelled, so the next play is not silent.
  The fade applies to **every** sleep timer, not only the bedtime one (the coordinator's call; Dewi delegated it,
  2026-11-06), over its last 20 s by default, and is switched off by setting its length to 0.
  There is no ramp on a live stream or an item with no known length; those still stop. On a cast the fade moves the
  TV's own volume (from where it was, and put back after) when the device reports one; when it does not, the line
  `no fade: casting to <device>` is logged and playback still stops at the end of the item.
- **Cancelling it sticks for the night.** Tap the moon button to cancel the armed timer and nothing re-arms it until
  the next night (remembered across a restart). That the cancel lasts until the night ends is the coordinator's call;
  Dewi delegated it, 2026-11-06. Only cancelling the timer bedtime armed counts: cancelling a timer you
  set yourself, after bedtime's own has fired or been replaced, does not turn bedtime off.
- It ships **off**.

## The page (Settings → 🌙 Bedtime sleep timer, or the last item in the sleep timer's menu)

| Card | What you can change |
|---|---|
| Status | one line (`💤 off`, `🌙 Bedtime starts at 22:00`, `🌙 It is bedtime until 06:00: what you play next will stop when it ends`, `😴 Stopping after this item, then fading out`, `🙅 Turned off for tonight`), and the switch |
| When | **Bedtime starts** (default 22:00), **Bedtime ends** (default 06:00), and the **Nights** it applies to (all seven by default; Friday means Friday evening to Saturday morning) |
| Fade out | **Fade length**, 0 (off) to 60 s in 5 s steps, default 20 s |

## Where it lives

- `lib/reminders` — `BedtimeSettings` (nights, start, end, fade; `isBedtime`, `nextStart`, `runEnd`, `decide`, `status`),
  `BedtimeContext`, `BedtimeDecision`, `BedtimeStatus`, `BedtimeCodec`. Built from `WeeklyWindow`: a night that crosses
  midnight is the start-to-midnight window on its day plus a midnight-to-end window on the next.
- `core/playback` — `SleepTimer` (the fade, `cancellations`) and `PlaybackController.setFadeLevel` (the Media3
  controller sets the player's volume; the fake records the levels).
- `app/…/bedtime/` — `Bedtime` (the hub: settings, the arming rule, the night's cancel), `BedtimeScreen`,
  `BedtimeActivity`; `ui/player/SleepTimerControl` adds the menu entry; `di/AppContainer` wires `Bedtime` and gives
  `SleepTimer` the fade.

## Diagnostics

`[bedtime]` lines say each decision with its inputs: arming (item, position, duration, fade, when the night ends) and
every reason for not arming (counted: first of each reason and every tenth), the person's cancel, settings changes and
loading. `[sleep]` lines say when a fade begins, reaches silence and when the volume is restored, and when the timer
fires or is cancelled by the person. Reports carry `bedtime.enabled`, `.nights`, `.startMinute`, `.wakeMinute`,
`.fadeSeconds`, `.status`, `.armedRunEnd`, `.declinedRunEnd`, `.skips` and `.lastDecision`.

## Not verified yet

That a volume change made through the `MediaController` reaches the session's player and is audible as a fade (no phone
or device run in this build), and the Bedtime page on a screen.

## Tests (code first, 2026-11-06; `BedtimeTest` covers who may decline the night)

Listed in [todos/bedtime-sleep-timer](../todos/bedtime-sleep-timer.md) under "Tests owed".
