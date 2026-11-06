---
title: Playing or queueing a Pi item never copies it to the phone
kind: todo
status: built
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Playing or queueing a Pi item never copies it to the phone

Built 2026-11-06: `QueueAutoDownloader` takes `atHome` and skips an item finished at home (logged once with the
reason); `copyHomeOnWifi` and `PhoneSide.copyOnWifi` default to off (a stored choice is kept). Tests owed: a queued
item finished at home is not fetched, and the default settings.

Dewi 2026-11-05: "if a file is on my Raspberry Pi and I play it … I don't want it to be downloaded".
`HomeCopyFirst` (core/data) is first in every phone download, including `QueueAutoDownloader.kt:254`, with no network
gate, so a queued item the Pi finished is copied on any network. Decided: the queue auto-download skips anything at
home; `copyHomeOnWifi` defaults to OFF (was true, `AppPreferences.kt:159,219`); "📥 Copy to the phone now" stays.
Tests: `HomeCopyFirstTest`, `HomeDownloadsTest`.
