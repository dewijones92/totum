---
title: Exsurge keeps a durable history of its settings changes
kind: adr
status: accepted
updated: 2026-11-13
---

# 162. Exsurge keeps a durable history of its settings changes

- Status: Accepted
- Date: 2026-11-13

## Context

Finding 160: on 5 November Dewi's Pixel changed its Exsurge settings overnight from "Tuesday 06:00" to "no days,
08:00", and the cause was never found. The only record was the log line each `updateSettings` call wrote, which the
report buffer had long forgotten, and the old diff helper split `ExsurgeSettings.toString()` on ", ", which breaks the
`activeDays=[MONDAY, TUESDAY]` field, the very one that changed.

## Decision

- Every settings change is kept in a `SettingsLog` (`:lib:exsurge`): the last 20 changes, each with its time, its source
  (the `updateSettings(source)` argument, or "reminder due, noticed by ..." for the off-reminder's own write) and a field
  diff (`field old -> new`, one entry per changed field, from the serialised settings, so a set or a list is one field).
- The log also keeps the settings it last saw. At start-up the controller compares what it loaded against that; a
  difference means something other than `updateSettings` changed the stored settings (a restore, a corrupt write,
  another writer), and is recorded as one change with the source `start-up: found changed since the last write`.
- The loaded settings are logged at start-up.
- The log is persisted beside the settings (`ExsurgePersistence.loadSettingsLog` / `saveSettingsLog`, the `exsurge`
  prefs file) and appears in the diagnostics state block as `exsurge.settingsHistory`.
- The old diff helper is replaced by `ExsurgeCodec.settingsDiff`, which also fixes the `exsurge settings from=...` log line.

## Consequences

- A report from the phone now says who changed what and when, or that nothing in the app did.
- Twenty entries of a few hundred characters each is a small write on every settings change.

## Tests

`ExsurgeControllerTest` (source, diff and time per change; an unchanged write leaves nothing; only the last 20; survives
a controller restart and shows in the diagnostics block; a change behind the controller's back is noticed at start-up)
and `SettingsLogTest` (diff, the limit, start-up noticing, the codec). Both run with `-Ptotum.exsurgeTests`.
