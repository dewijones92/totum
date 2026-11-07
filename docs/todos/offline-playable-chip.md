---
title: Offline at a glance
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Offline at a glance

Dewi 2026-11-06. "✈️ N playable now" on the queue's offline summary and a filter to just those, by `routeNow`. Today
`unavailableOfflineNow` ignores audio-only copies that `routeNow` honours; the two must agree first.

## Built 2026-11-06

The queue's offline summary has a "✈️ N playable now" chip that filters the queue; `unavailableOfflineNow`, the count and
the filter all ask `routeNow` through `playsWithNoSignal`. Detail: [offline-playable-chip](../features/offline-playable-chip.md),
[ADR 136](../adr/0136-offline-playable-is-whatever-routenow-plays-from-the-device.md).

My calls: the filter is a fourth choice (✈️ Plays offline) in the shared "On the phone" row, so every list's sheet shows
it (equal to Downloaded outside the queue); the chip says "play with no signal" while online, since "now" would be
untrue. The disagreement the todo named was a handle that carries its own file, not audio-only copies, which already
counted.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`PlaysWithNoSignalTest` (`:core:domain`, 5: downloaded whole and audio-only for a video and a podcast, a handle with its
own file, nothing local, a torrent with only an audio address, and that the row line, count and filter ask one
question), `UnavailableOfflineNowTest` (the own-file case), `ItemFiltersTest` (`PLAYS_OFFLINE` by the extra answer and
by the default, `DOWNLOADED`/`NOT_DOWNLOADED` ignoring it, the live filter). Seen failing with `offline = false` in the
rule.

Still owed: instrumented, the chip showing and toggling the queue filter (`QUEUE_PLAYABLE_CHIP_TAG`) and the saved
filter round-tripping `PLAYS_OFFLINE` through `rememberItemFilters`.
