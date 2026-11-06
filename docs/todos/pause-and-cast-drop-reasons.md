---
title: Every pause and cast drop says why; a flight recorder
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Every pause and cast drop says why; a flight recorder

Reports 20261101T170416 and 20261105T064409 could not say who paused, and this morning's Exsurge wake-up was lost
because the trail held only 25 minutes. Decided:
- log Media3's `onPlayWhenReadyChanged` reason and the suppression reason (`PlaybackDiagnostics.kt:265`);
- log cast `onSessionSuspended`/ending reasons with names for codes like 2055/2155 (`CastTarget.kt:38-47`);
- flight recorder: on a stall give-up, a cast drop or a summons outside active hours, write the trail to disk so a
  later report includes it (`reportNonFatal` has no callers; `StatsLog` JSONL is the on-disk precedent); keep
  side-quest and cast lines longer than playback lines. The ingest endpoint truncates bodies over 8 MiB.
