---
title: A daily mobile-data budget
kind: todo
status: built; tests written in part
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# A daily mobile-data budget

Dewi 2026-11-06. Count today's mobile data (persisted, reset daily); past the user's limit, video plays as sound
only with a line saying so. `MeteredAudioSwitch` already does sound-only on metered networks.

Built: see [../features/mobile-data-budget.md](../features/mobile-data-budget.md).

## Tests written 2026-11-14

`MobileDataMeterTest` (metered versus unmetered deltas, reboot, midnight, unsupported counters, a restart, 20,000 samples, `reachedLimit`, `holdingBack`), `MeteredAudioSwitchOverLimitTest`. Still owed: the Settings row choices and `SharedPrefsMobileDataStore` (needs `SharedPreferences`).
