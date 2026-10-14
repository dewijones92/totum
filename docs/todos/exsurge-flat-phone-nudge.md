---
title: Exsurge nudges when the phone has lain flat while it is off
kind: todo
status: built 2026-10-14 — ADRs 37, 38
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
- At most once an hour, with **Not today** to stop it until tomorrow; one short buzz, no sound (Dewi,
  2026-10-14). Also built that day: Off means off for today ([ADR 38](../adr/0038-exsurge-off-means-off-for-today.md)).
- Not the sitting clock: flat-on-the-table does not become "sitting" (pocket-sitting would be missed).

## Notes for the build

- Accelerometer with batching (`maxReportLatencyUs`), gravity within a few degrees of the z axis and
  variance under a threshold, judged in a pure rule in `:lib:exsurge` with its tests.
- Needs listening while Off, when no Exsurge service runs today: decide between the idle banner's
  process, a short exact alarm sampling window, or `TYPE_STATIONARY_DETECT` where the phone has it.
  Measure battery on the Pixel before calling it done.
- Log every decision: flat since, still since, nudged or why not (outside hours, already nudged, on).
