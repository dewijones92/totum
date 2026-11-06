---
title: A Quick Settings tile sends a Totum report
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# A Quick Settings tile sends a Totum report

Dewi 2026-11-06. A tile opens the diagnostics note box directly. Reuse the alarm tiles' pattern
(`DailyAlarmTileService`, `NewAlarmTileService`) and extract the note dialog from `SettingsScreen.kt:337-382`.
