---
title: Search in Settings
kind: todo
status: built; tests written in part
batch: A (2026-11)
priority: low
updated: 2026-11-14
---

# Search in Settings

Dewi 2026-11-06. Settings rows are composables, not data (`SettingsScreen.kt:76-146`), so search needs a small
registry (label, section, destination) including the sub-pages (Exsurge, alarms).

Built 2026-11-06: see [the feature doc](../features/settings-search.md). `SettingsRow` is the registry; tests to follow.

## Tests written 2026-11-14

`SettingsRegistryTest` (every row on the main page is anchored, every anchor names a row, no two rows share a name and section, the result's second line). Still owed: a Compose test that a pick scrolls to and tints a row far down the page.
