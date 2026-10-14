---
title: A bold Roman redesign of the Exsurge and Daily alarms screens
kind: todo
status: open — designed with Dewi 2026-10-14, not started
area: side-quest/ui
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# A bold Roman redesign of the Exsurge and Daily alarms screens

Dewi, 2026-10-14: *"please improve the UI of the exsurge page and alarm page??? sexy???? while keeping
functional"*. Top of the backlog at his request; not to be coded until he picks it up.

## Agreed (Dewi, 2026-10-14)

- **Vibe: Roman and bold.** Exsurge leans into its character: a big Surgius hero, laurel-gold accents,
  the Latin rank as a badge, a countdown ring ("12:34 until you rise") and the status actions as
  buttons under it. The alarms screens get a matching big-clock look. Still in the Totum palette.
- **Add a laurel gold** to `theme/Color.kt` (light and dark variants), used only on the side-quest
  screens for laurels, ranks and accents. The palette stays in that one file.
- **Exsurge settings: everything stays visible**, grouped into cards with icons (hours, timing,
  behaviour, permissions, destination). Nothing folded away.
- **Number settings become sliders** showing value and unit ("30 min"), snapping to the same steps
  and ranges as today's −/+ steppers (`ExsurgeSettings.*_RANGE`).
- **Each alarm is a compact card**: name, on/off switch, a huge next time, today's state, the
  "Set for today" chips and the day initials, with an **Edit** that opens the day chips, per-day times,
  choices and ask times.
- **In scope:** the Exsurge page, the Daily alarms page, the Exsurge takeover screen and the alarm
  ring and morning-question screens.

## What exists

- Exsurge page: `ui/settings/ExsurgeSettingsScreen.kt` (one scrolling column: status card, stats card,
  10 steppers, 5 switches, permission checks, destination fields) and `exsurge/ExsurgeStatusCard.kt`.
- Takeover: `exsurge/TakeoverActivity.kt`, Surgius drawn by `exsurge/Surgius.kt`.
- Alarms page: `dailyalarms/DailyAlarmsScreen.kt` (one card per alarm with every control open).
- Ring and question: `dailyalarms/RingActivity.kt`, `dailyalarms/QuestionActivity.kt`.

## Keep functional

- Every control and test tag the current UI tests use (`exsurge-status*`, `exsurge-action-*`,
  `dailyalarm-*`) keeps working, or the tests move with it in the same change.
- No truncation (text wraps; preflight enforces it). Check dark mode as well as light.
- Screenshot each changed screen on the emulator before calling it done.

## Open

- How the build is run when picked up (worktree or the main working copy) and how it ships: Dewi
  said "backlog it" to both on 2026-10-14, so ask when it starts.
