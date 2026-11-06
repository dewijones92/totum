---
title: Offline at a glance
kind: feature
status: built 2026-11-06, Batch A (tests owed)
area: queue
updated: 2026-11-06
---

# Offline at a glance

Dewi, 2026-11-06. [ADR 136](../adr/0136-offline-playable-is-whatever-routenow-plays-from-the-device.md).

## What it does

Under the queue's offline summary line there is a chip: **✈️ 12 playable now** with no network, **✈️ 12 play with no
signal** while there is one. It counts the queue entries the queue would really play from the phone. Tapping it
filters the queue to just those; tapping it again, or "Clear" in the sort and filter sheet, brings the rest back.

The same choice is in the sort and filter sheet of every list as **✈️ Plays offline** under "📥 On the phone". In every
list but the queue it shows what "✅ Downloaded" shows, because only the queue knows a handle's own file.

## One rule

`PlayableItem.playsWithNoSignal(state)` asks `routeNow` with `offline = true`. `unavailableOfflineNow` (the row's
"will be skipped" line), the chip's count, the filter and the diagnostics all use it, so they cannot disagree with
what playback does.

## Diagnostics

`downloads.queuePlaysWithNoSignal` in every report. Toggling the chip logs `queue` ... `offline filter on/off: N of M
entries play with no signal (offline now=...)` with `dewidebug`.

## Tests owed

- `PlaysWithNoSignalTest` in `:core:domain`: downloaded (full and audio-only), a handle with its own file but no
  download state, not downloaded, downloading, a Video handle with nothing local, a torrent with an audio URL only.
- `UnavailableOfflineNowTest` already covers the state cases through the new extension; add the own-file handle case.
- `ItemFiltersTest`: `PLAYS_OFFLINE` accepts by the extra answer and defaults to `downloaded`.
- Instrumented: the chip shows and toggles the queue filter (`QUEUE_PLAYABLE_CHIP_TAG`), and the saved filter
  round-trips `PLAYS_OFFLINE` through `rememberItemFilters`.

**Hidden at zero (2026-11-06):** the chip shows only when at least one item would play with no signal, or while its
filter is on (so it can be switched off). On a short screen "✈️ 0 play with no signal" took a row from the queue and
said nothing (found while fixing the CI queue tests).
