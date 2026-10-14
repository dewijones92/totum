---
title: Exsurge nudges when the phone has lain flat while it is off
kind: todo
status: open — agreed with Dewi 2026-10-14; build after the 2026-10-14 soak
area: side-quest
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# A nudge when you have sat down and Exsurge is off

Dewi, 2026-10-14: *"what happens when I forget to turn on the exsurge thing? … nudge?? detect when the
phone has become flat on my table? (i.e. sat down?)"*. Today Off stays off; the only reminder is the
silent always-present banner.

## Agreed

- While Exsurge is **off**, inside its **active days and hours**, if the phone has lain **flat and still
  for 1 minute** (Dewi changed it from 5 on 2026-10-14), post one notification: "Sat down? Turn Exsurge on", with **Turn on**.
- At most once an hour; not while a call, a ringing alarm or a summons is up.
- Not the sitting clock: flat-on-the-table does not become "sitting" (pocket-sitting would be missed).

## Notes for the build

- Accelerometer with batching (`maxReportLatencyUs`), gravity within a few degrees of the z axis and
  variance under a threshold, judged in a pure rule in `:lib:exsurge` with its tests.
- Needs listening while Off, when no Exsurge service runs today: decide between the idle banner's
  process, a short exact alarm sampling window, or `TYPE_STATIONARY_DETECT` where the phone has it.
  Measure battery on the Pixel before calling it done.
- Log every decision: flat since, still since, nudged or why not (outside hours, already nudged, on).
