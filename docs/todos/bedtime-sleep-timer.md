---
title: A bedtime sleep timer
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11)
priority: low
updated: 2026-11-07
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

## Tests (Batch A testing stretch, part A, 2026-11-07)

`BedtimeSettingsTest` (`:lib:reminders`, now 10: nights through midnight, chosen nights, equal times, every decision
branch and its reason, the status, clamping, the codec ignoring unknown keys, a night ending at its own morning, the
next start skipping an unchosen night, a declined night arming again the night after), `BedtimeHubTest` (`:app`, 13:
arms on play inside bedtime and not outside, a timer already armed, the kid lock, live, switched off, already playing
when bedtime starts, switching it on, cancel sticking for the night and the next night arming, the declined night
persisted and not re-armed by a restart, a stale decline forgotten, clamped saves, the status moving, the report),
`SleepTimerFadeTest` (`:core:playback`, 12: countdown fade holds then ramps down and ends silent and paused, fade 0, the
last seconds of an item, at speed, live and no length, cancel and moving off and restart restoring the volume, the TV
volume ramped from where it was and put back, a TV with no volume, who armed a cancelled timer), `BedtimeTest`. Seen
failing with the volume restore removed and the decline no longer saved.

Still owed: the audible fade on a phone, the Bedtime page, the page's status line moving at a boundary while open.
