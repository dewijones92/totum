---
title: Stats
kind: feature
area: library
status: built — awaiting a week of real use on Dewi's phone
updated: 2026-10-14
---

# Stats

Library → **📊 Stats** (ADR 44). Everything is computed when the page opens from the event log
(`files/stats/events-YYYY-MM.jsonl`) and Exsurge's stored outcomes.

| Section | What it shows | Drill-down |
|---|---|---|
| Period | Today, Week (default), Month, Year, All time | — |
| 🎧 Listening | plays and time for everything, music, podcasts, videos; watched vs listened; 🔥 streak and longest | — |
| 📈 Over time | bars per hour (today), day (week, month) or month (year, all time); plays or hours | tap a bar: its plays |
| 🏅 Top | artists, songs, albums, shows, channels, by counted plays then time | tap a row: its plays |
| 🕰️ When you listen | weekday × hour heatmap of time heard | — |
| 🏛️ Exsurge et Disce | summons, obeyed, skipped, missed, snoozes, Just walk, paused, steps, standing, practised, time off | — |
| 📱 Flat-phone nudges | nudged, turned on (within 15 min), not today, ignored | — |
| ⏰ Daily alarms | asked, yes, no, rang, snoozed, stopped, average time to stop (snoozes included) | — |
| 🧭 App use | searches, downloads and MB, pins, shuffles, videos shown | — |

A play **counts** once 30 s or half of it has been heard. Recording started on 2026-10-14; Exsurge reaches back
through its saved outcomes.

## Where it lives

- `lib/stats/` — `StatEvent`, `StatsCodec`, `ListenStats.kt`, `Bar.kt`, `SideQuestStats.kt` (pure, unit-tested).
- `app/…/stats/` — `StatsLog` (file log), `ListenRecorder`, `DownloadStats.kt`.
- Hooks: `ExsurgeController` (`record`), `DailyAlarmController` (`record`), `FlatPhoneNudge`,
  `ExsurgeActionReceiver`, `SearchViewModel`, `Pins.kt`, `ShuffleAll`, `ArtistShuffle`, the album page, the
  Queue's shuffles, the Videos tab's Show videos.
- `app/…/ui/stats/` — `StatsViewModel`, `StatsScreen`, `StatsParts`, `StatsCharts`.

## Logged

`[stats] listen began: … as MUSIC (queued=true)`, `listen ended (why): … heard Nms of Mms, counts=…`,
`recovered a play cut off …`, `download finished: … bytes`, `stats read N event(s) from F file(s);
unreadable lines: U`, `showing WEEK: …`, `drill into "…": N listen(s)`.

## Tests

`:lib:stats` `ListenStatsTest`, `SideQuestStatsTest`; `:app` `ListenRecorderTest` (heard time, pauses, recovery
after the process dies), `StatsViewModelTest` (periods, both drill-downs).
