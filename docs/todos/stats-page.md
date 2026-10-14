---
title: A stats page with drill-downs
kind: todo
status: open — agreed with Dewi 2026-10-14; log in the 2026-10-14 batch, page in the next
area: library
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# Stats page

Dewi, 2026-10-14: *"I want a stats page in the app … how many times I pressed walk, how many songs I listened
to, how many podcasts, hours of podcasts … pretty rich stats page with drill downs … how many exsurge I
ignore or I obeyed"*.

## Agreed

- **Recording starts now:** the event log (no UI) goes into the 2026-10-14 batch so data accumulates
  before the page exists; the page follows in the next batch.
- **Where:** a Stats card at the top of Library. **When:** the batch after the 2026-10-14 batch has been
  reviewed, pushed and soaked.
- **A listen** counts once 30 s or half of the item has been heard, whichever comes first. Hours are the
  time actually heard, every second, for both pillars.
- **Drill-downs:** all four.
  - Time periods: today, week, month, year and all time, with a bar chart; tap a bar for that day.
  - Top lists: artists, songs, albums, podcast shows and channels; tap one for its plays and hours.
  - When I listen: a time-of-day by weekday heatmap, plus the longest streak of days.
  - Per pillar: music, podcasts and videos, as plays and as hours, plus listen versus watch.
- **Side quests:** all four.
  - Exsurge outcomes: obeyed, skipped, missed, snoozed, "Just walk" presses, steps, minutes standing,
    Loquax practice, and rank over time.
  - Nudges and off time: flat-phone nudges and what followed (turned on, not today, ignored), and the hours
    Exsurge was off or paused.
  - Daily alarms: asked, said yes, rang, snoozed, dismissed, and how long each took to stop.
  - App use: searches, downloads (count and GB), pins, shuffles, and how often the videos were shown.

## What exists today (checked 2026-10-14)

- `play_history` keeps the **50 most recent items**, one row each (upsert by id), with no play count and no
  time heard. Media stats need a new append-only listening log (a Room table: item columns, started at,
  ms heard, whether it counted), so they **start from the build that adds it**. There is nothing to
  back-fill from.
- Exsurge keeps its last **5,000 `BreakOutcome`s** (kind, snoozes, steps, break start, practised), so its
  outcome stats back-fill. "Just walk" presses, nudges and off/paused time are not recorded as events
  today and start when the log does.
- Daily alarms keep per-day state only; their events start with the log.

## Shape

One event log seam in `:core:domain` (a sealed `StatEvent`), one store, and pure aggregation functions
(periods, top lists, heatmap, streaks) unit-tested off Android. The page reads those aggregates. Every
feature appends events through the one port, so no pillar gets its own counter.
