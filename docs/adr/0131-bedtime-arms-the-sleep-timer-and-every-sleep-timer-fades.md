---
title: Bedtime arms the one sleep timer, and every sleep timer fades out
kind: adr
status: accepted
updated: 2026-11-06
---

# 131. Bedtime arms the one sleep timer, and every sleep timer fades out

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-05 (agreed idea, Batch A phase 4): after 22:00 the sleep timer should stop at the end of the current item
and fade out, with the times configurable. The sleep timer already exists (`SleepTimer`: a countdown, "after this item",
"at the end of this album") and the queue already asks it as each item ends. It had no fade, and nothing started it for
you. The weekly-window maths was extracted for the work-apps feature (ADR 124) so a third copy would not be written.

## Decision

- **Bedtime drives the existing `SleepTimer`; it is not a second timer.** `Bedtime` (in the app's `bedtime` package)
  calls `SleepTimer.stopAfterCurrentItem()` when something starts playing during bedtime, so the player's moon button,
  the Quiet-modes chip and the queue's end-of-item question all see an ordinary "after this item" timer.
- **When it arms.** Evaluated when playback starts (or moves on to another item while playing), when bedtime begins
  while something plays (a coroutine sleeps until `BedtimeSettings.nextStart`, re-checked at least every 6 h) and when
  the settings change. The decision is one pure function, `BedtimeSettings.decide` in `:lib:reminders`: switched off,
  outside bedtime, a sleep timer already armed, the kid lock on, a live stream (it never ends) or the timer turned off
  for tonight → skip, with the reason logged (counted, so a busy night does not fill the report); otherwise arm.
- **Bedtime is a night, built from `WeeklyWindow`.** Settings: the nights (days of the week), a start (default 22:00)
  and an end (default 06:00), plus the fade. `WeeklyWindow` holds one range per day, so a night that crosses midnight
  is two windows: the start to midnight on the night's day, and midnight to the end on the next. `runEnd` follows the
  run through midnight, and the night is identified by its end instant. A start before the end (01:00 to 06:00) is one
  window on the same day.
- **Cancelling it sticks for the night.** `SleepTimer.cancel()` (the person's tap) now also emits on `cancellations`;
  internal ends (the item finishing, the timer firing, playback moving off) do not. If the person cancels a timer that
  Bedtime armed, that night's end is remembered and nothing re-arms until the next night. In memory only: a process
  death forgets it (my call; the cost is one extra arming).
- **Every sleep timer fades, not only bedtime's.** `SleepTimer` takes a fade length (`Bedtime.fade`, default 20 s, 0 =
  off, set on the Bedtime page) and ramps `PlaybackController.setFadeLevel` (the player's own volume, a new port method
  implemented on the Media3 controller and the fake) over the last stretch: a countdown over its final seconds, an
  "after this item" or "end of album" timer over the final seconds of the last item (from its duration, position and
  speed). The level goes back to full the moment the timer ends or is cancelled, so the next play is never silent. No
  fade on a cast, a live stream, or an item with no known length; those still stop, without a ramp. My call to apply it
  to every timer rather than only bedtime's: one timer should behave one way, and a setting can switch it off.
- **Settings** live on a "🌙 Bedtime sleep timer" page, reached from Settings and from the sleep timer's menu, stored as
  JSON (`bedtime_sleep_timer`). It ships **off**.

## Consequences

- A sleeper who wakes at 03:00 and plays something gets the timer again (a fresh play re-arms), unless they cancelled
  the timer earlier that night.
- Skipping to the next item while a bedtime timer waits pauses playback (the sleep timer's existing rule for moving off
  its item), then the next play re-arms it.
- The fade is a linear volume ramp at the player, below any volume boost; audio ducking and the phone's volume are
  separate and untouched.
- `SleepTimer`'s constructor gained a defaulted fade provider and `PlaybackController` gained `setFadeLevel`; both
  existing implementers are updated.
- Unverified until a phone run: that a `MediaController.volume` change reaches the service's player (the command is
  `COMMAND_SET_VOLUME`) and is audible as a fade.
