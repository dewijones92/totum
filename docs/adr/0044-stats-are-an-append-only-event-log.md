---
title: Stats are an append-only event log, aggregated when the page opens
kind: adr
status: accepted
updated: 2026-10-14
---

# 44. Stats are an append-only event log, aggregated when the page opens

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"I want a stats page in the app … how many times I pressed walk, how many songs I listened
to, how many podcasts, hours of podcasts … pretty rich stats page with drill downs … how many exsurge I ignore
or I obeyed"*. Little of it was stored: history kept the 50 newest items with no counts or time; daily alarms
kept only today; searches only the last 10. Exsurge kept its last 5,000 outcomes.

## Decision

- **A pure module, `:lib:stats`**: a sealed `StatEvent` (`Listen`, `ExsurgeAction`, `ExsurgeSwitch`, `Nudge`,
  `AlarmChange`, `AppUse`) and every aggregation as a pure, unit-tested function (totals, bars, top lists,
  heatmap, streaks, Exsurge, nudges, alarms, app use).
- **An append-only log of JSON lines**, one file per month under `files/stats/` (`StatsLog`), written on one
  thread. Not Room: no schema bump, which would block a soak's baseline build. An unreadable line is counted and
  skipped.
- **A listen** is one record per play (`ListenRecorder`, sampling the player once a second): time heard while
  playing, whether a picture was shown, and its kind (music by `MediaContentKind.MUSIC`, else podcast or video
  by pillar). It **counts** at 30 s or half the item, whichever is first (Dewi's choice). The play in progress
  is saved every 30 s and recovered on the next start, so a killed process loses at most 30 s.
- **Exsurge outcomes back-fill** from its stored 5,000; presses, nudges, off time, alarm changes, searches,
  finished downloads, pins, shuffles and "Show videos" are recorded from this release on.
- **The page** (Library → 📊 Stats): Today / Week / Month / Year / All time; plays and time by kind, watched
  versus listened, streaks; bars over time (plays or hours, tap a bar for its plays); top artists, songs,
  albums, shows and channels (tap one for its plays); a weekday × hour heatmap; cards for Exsurge, nudges,
  alarms and app use.

## Consequences

- Sampling once a second costs up to a second at each end of a play (38 s recorded for 40 s in the test).
- The whole log is read when the page opens: about 200 bytes a play, so a year of heavy use is a few MB.
- Not in backups yet, and nothing prunes the log.
- Verified on `totum-api35`: a video played 49 s was logged and counted; the page showed it, the week's bars,
  the heatmap cell, and 38 Exsurge summons from its history.

## Amended 2026-10-14, after the batch review

- **Names in the log are enums, never class names.** The first version stored `::class.simpleName` for Exsurge moves
  and alarm events and compared them with literals; R8 renames those classes in a release build (the repo had met
  this before as `handle=wr3`), so every alarm number and the Just walk and pause counts would have read 0 on the
  phone. `ExsurgeMove`, `AlarmStep` and `AlarmPhase` are mapped from the sealed types by exhaustive `when`s, and
  kotlinx.serialization writes their names from generated code. Rows written by v0.1.608 with the old names are
  unreadable and skipped (counted in the `stats read` line).
- **The page reads the log every time it opens** (`StatsViewModel.reload`), not once per activity.
- The play in progress is written to a temporary file and renamed into place; its length is refreshed every tick,
  so "half the item" holds when the length arrives after the play began.
