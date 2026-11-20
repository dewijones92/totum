---
title: Exsurge lengths are sliders at the summons, a walk says it is a walk, and five steady minutes of walking finish a break
kind: adr
status: accepted
updated: 2026-11-20
---

# 181. Exsurge: lengths are sliders at the summons, a walk says it is a walk, and five steady minutes finish a break

- Status: Accepted
- Date: 2026-11-20

## Context

Dewi, 2026-11-20, after reading the diagnostics of 14 to 20 November:

- *"Exsurge summons duration slider"*: the takeover offered the break and snooze lengths as rows of chips (2 / 5 / 10 / 15
  and 5 / 10 / 15 / 30), a different control from the sliders the rest of the Exsurge screens use.
- Report 20261116T134953 (0.1.927) was captioned *"the current timer is not working. it like stays within like"* and the
  countdown sat near 19 minutes while he walked. The cause was by design and nowhere explained: 100 steps inside the 5-minute
  walking window dispatch `Walked`, and `ExsurgeMachine.walked()` restarts the sitting clock. His walk that morning
  (13:39 to 13:50, 16 November) reset the clock every 65 to 135 seconds, so the clock never got past about two minutes and
  the day tally earned nothing for a ten-minute walk.
- *"yes to all"* to the proposals to say so in the banner, and to count five minutes of steady walking as a finished break.

## Decision

### 1. The summons screen uses sliders

The takeover's break length and snooze length are `SnappingSlider`s, the control the Exsurge settings, Restart clock,
Bedtime and Phone use screens already share. Nothing new was written.

- **Break length:** 1 to 15 minutes in 1-minute steps (`ExsurgeSettings.BREAK_RANGE`). Every old chip (2, 5, 10, 15) is a
  position on it, and so is every value the settings stepper can hold.
- **Snooze length:** 5 to 60 minutes in 5-minute steps, which contains every old chip (5, 10, 15, 30). A snooze length
  that is off that grid (the settings stepper goes in 1-minute steps) puts the slider on 1-minute steps for that visit, so
  the slider always shows the number that will happen, never a rounded one.
- Both still write the one setting and log `takeover break length chosen=Nm` / `snooze length chosen=Nm`, and GO, Just walk
  and Continue Totum still copy the break length onto the summons as the break begins, so a break's length is fixed for that
  break ([ADR 10](0010-break-length-is-chosen-at-the-summons.md)).

### 2. A walk says it is a walk

`Sitting` remembers when walking last reset the clock (`walkedAt`). While that was under two minutes ago
(`WALKING_SHOWN_FOR`, longer than the 65 to 135 s between resets measured on 16 November) the status is a `BannerLine.Walking`
and every surface says so from it:

- **Banner and Exsurge page hero:** the title reads `🚶 Walking · timer reset 40 s ago` (seconds under a minute, then
  minutes), the detail adds `steady walk 3 of 5 min`, the ring shows the steady-walk progress and Surgius cheers instead of
  sitting content.
- **Live Update chip:** `🚶 3/5` instead of a countdown that keeps jumping back.
- **Quick Settings tile:** `🚶 Walking`.

The notification is re-posted on every reset and each minute, so its "N s ago" is true when it is drawn and can be up to a
minute old after that. "Steps toward the next reset" (the optional "62 of 100") was not built: the steady-walk minutes already
answer "is it counting".

### 3. Five steady minutes of walking are a finished break

`SteadyWalk` (`:lib:exsurge`, pure, fed every step reading with its sensor time) watches for consecutive minutes with at least
`steadyWalkStepsPerMinute` steps.

- **Steady** is: from the first step of a stretch, five (`steadyWalkMinutes`) back-to-back minutes that each hold at least
  40 steps (`steadyWalkStepsPerMinute`). A minute that falls short, or a gap with no readings, breaks the stretch and the next
  step starts a new one. Four minutes do not count, however brisk. Minutes are anchored to the stretch's first step, never to
  the wall clock, so a walk that starts at 10:00:40 is not penalised for crossing :00.
- **Why 40:** on 16 November 13:39 to 13:50 Dewi's walk measured a mean of 78 steps a minute (minimum 50 in any 60-second
  window, interpolated from step totals logged every 45 s or so) and the 15-minute walking break after it a mean of 88 (minimum
  84). Forty is about half the pace he walks at, so a door, a tap and a slowed corridor do not break a stretch, and fidgeting
  at a desk cannot reach it for five minutes. Both numbers are Exsurge settings with those defaults, on the Timing card
  (minutes 2 to 15, steps 10 to 150).
- **One credit per walk:** once a stretch has been credited, walking on earns nothing more; a minute under the threshold
  (or a gap) ends that walk and the next five steady minutes are another break. Without this a 15-minute walk would earn
  three laurels where the same 15 minutes as a summons break earn one. (Claude's choice, not asked; the banner keeps
  showing "5 of 5" while the walk goes on.)
- **What it does:** from a sitting clock, with the schedule on, the stretch's completion is one `SteadyWalk` event. The
  machine records a `COMPLETED` outcome (the stretch's start as summoned and as break started, its steps, proven and
  required, not practised), so it earns a laurel, counts in `completed`, `steps` and `minutesStanding` for the day and the week,
  and can promote, exactly as a summons break does. It speaks `Satis! Liber es!`, gives the short steps-accepted buzz and starts a
  fresh sitting clock (Surgius shows his freed face for a minute). The stats log records the action as `STEADY_WALK`.
- **Off still means off ([ADR 37](0037-exsurge-nudges-a-flat-phone.md), [71](0071-exsurge-off-stays-off-and-reminds-once-a-day.md)):**
  nothing is counted, voiced or recorded while the state is Off, Paused or Dormant, or the schedule is off (a manual one-off
  clock with the schedule off is not a streak either). A summons, snooze, rise or break in progress is never interrupted:
  the tracker is only fed while the state is Sitting and is emptied the moment the state leaves it.
- **Step counter batching:** while walking is shown the banner service listens unbatched (as it does for a rise and a break),
  so the fifth minute is noticed within seconds, not up to a minute late.
- **Logged:** each decision is a `dewidebug exsurge steady walk` line with its inputs (the streak's start, the minute, steps
  against the threshold, why a stretch broke, what was ignored and why), at most once a minute.

## The flip-flop of 16 November

`enabled` went false to true to false six times that day (10:07, 10:24, 10:45, 13:51, 14:51 and 17:27), the two writes 0.35 to 0.72 s apart, each with the source `settings`. The only code
that writes `enabled` with that source is the Exsurge page's switch (`onCheckedChange`, `update { copy(enabled = on) }`), and one
touch calls it once, so each pair is two touches at a human double-tap rhythm. Nothing in the app writes it twice (the tile,
the banner's Turn on and the nudge each have their own source). What the first touch produced explains the second: the
settings on the phone have **no active days** (`days=  08:00-22:00`), so switching on goes to `Dormant until never` and the
banner reads `😴 Surgius sleeps · no active days set` with a `😴 Zzz` chip, which reads as "not on". That is a hypothesis from the
code and the log, not proven (the touch itself is not recorded). The same empty days is the most likely reading of the
unexplained 5 November change ("Tuesday 06:00" to "no days, 08:00", finding 160). So the page's controls now write distinct
sources (`page switch: enabled`, `page slider: <name>` ...), and a flip of `enabled` that lands within two seconds of the previous
one is noted in the history itself (and logged) with the gap, the previous source and the state it left, so the next
report can settle whether it was a double tap. Nothing was suppressed: a real double tap still toggles twice.

## Consequences

- The takeover loses four chips and gains two sliders; a chip test in `ExsurgeTakeoverFlowTest` becomes a slider test.
- A walk of ten minutes is one finished break where it used to be nothing (the clock only kept resetting). The sitting clock
  after it starts fresh, so a summons will not come until 30 minutes after the walk.
- `Sitting` gained `walkedAt` and the settings two fields; older saved state and settings decode with the defaults.
- Not built, on purpose: a switch to turn the steady-walk credit off (the two settings can be set to their limits instead),
  and "steps toward the next reset".

## Tests

`:lib:exsurge` (opt-in phase, `-Ptotum.exsurgeTests`): `SteadyWalkTest` (five minutes complete, four do not, a gap or a slow
minute breaks, exactly the threshold passes, counter reboot, out-of-order readings), `ExsurgeMachineTest` (a steady walk
records a credited break and starts a fresh clock; ignored when off, paused, dormant, summoned or on a break; a walk reset
remembers when), `ExsurgeSupportTest` (the walking line, chip, ring and mood). `:app`: `ExsurgeControllerTest` (readings in, a
credited break out, stats and day tally, the history source and the flip-flop log), `SummonsSlidersTest`.
