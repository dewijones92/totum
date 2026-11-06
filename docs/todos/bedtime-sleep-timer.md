---
title: A bedtime sleep timer
kind: todo
status: built 2026-11-06, Batch A (tests owed)
batch: A (2026-11)
priority: low
updated: 2026-11-06
---

# A bedtime sleep timer

Dewi 2026-11-05 (agreed idea): after 22:00 the sleep timer stops at the end of the current item and fades out; times configurable. Reuse the existing sleep timer.

Whole-batch review: uses the shared `WeeklyWindow`; moved to phase 4. `SleepTimer` has no fade yet.

## Built 2026-11-06

Done as [features/bedtime-sleep-timer](../features/bedtime-sleep-timer.md), decided in
[ADR 133](../adr/0133-bedtime-arms-the-sleep-timer-and-every-sleep-timer-fades.md). Dewi's batch rule: code first. The
bedtime logic drives the existing `SleepTimer` (`stopAfterCurrentItem`); the fade went into that same timer. Not yet seen
on a phone: that the fade is audible through the session's player.

My calls (not Dewi's): the fade applies to every sleep timer, not only bedtime's (a setting turns it off); a night is
nights-of-the-week plus a start and an end, not per-day hours; cancelling the armed timer sticks until the night ends,
held in memory only; no auto-arm for live streams or the kid lock.

## Tests owed

- `BedtimeSettingsTest` (`:lib:reminders`): `isBedtime` across midnight (Friday 23:00, Saturday 03:00, Saturday 07:00),
  a start before the end on one day, no nights, start equal to end, `runEnd` through midnight, `nextStart`, `decide`
  for every branch (off, outside, armed, kid lock, live, declined, arm) and `status`.
- `BedtimeCodecTest`: round trip, unknown keys, `validated` clamps.
- `SleepTimerTest` (`:core:playback`, unit): the fade ramps `setFadeLevel` over the last seconds of a countdown and of
  the last item (position and speed), restores full volume on end, cancel and moving off, never fades with fade 0, on a
  live item or with no duration; `cancel()` emits on `cancellations` and `firesAfter` does not.
- `Bedtime` hub tests (fake controller, test scope): arms on play start inside bedtime, not outside, not when a timer
  is armed, not when kid-locked or live; arms at the start of bedtime if already playing; cancel sticks for the night
  and the next night arms again; settings change evaluates.
- A device check of the audible fade, and of the Bedtime page.
