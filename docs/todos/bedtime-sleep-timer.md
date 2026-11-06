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

The coordinator's calls, which Dewi delegated on 2026-11-06 ("your call on each"): every sleep timer fades out over its
last 20 s, not only bedtime's (a fade length of 0 turns it off); cancelling Bedtime's own timer keeps Bedtime off until
that night ends. Also the coordinator's: a night is nights-of-the-week plus a start and an end, not per-day hours; no
auto-arm for live streams or the kid lock. (The "held in memory only" part was reversed: the cancel is now kept across a
process death, see ADR 133's amendment.)

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
- Sweep fixes (2026-11-06), still owed: `SleepTimerTest` for the cast fade (ramps `setCastVolume` from the starting
  fraction and puts it back; a cast with no `castVolume` logs and does not ramp; `cancellations` carries the owner
  of the timer that was cancelled and null for a timer the person set); `Bedtime` hub: the declined night is written
  to the store and a new `Bedtime` over the same store does not re-arm that night but does the next; the page's
  status line moves at a boundary while open (an instrumented/UI check). Finding 5's test is written: `BedtimeTest`.
