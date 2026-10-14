---
title: Recent actions (shuffle all, albums, radios) come back as a Recently row and app shortcuts
kind: todo
status: built 2026-10-14 — ADR 36
area: queue/ui
priority: medium
requested: 2026-10-14
updated: 2026-10-14
---

# Recently: do it again

Dewi, 2026-10-14: *"if I shuffle all songs of artistX does that appear as a recent action I do just do as
a shortcut somewhere???"*. Today it does not: pins are manual and hold an item, album, artist or playlist.

## Agreed

- Remember the last few **actions**: shuffle all of an artist, channel or podcast; an album played or
  shuffled; a radio started. Both pillars.
- Show them as a **Recently** row on the **Music home** and in **Library**, and as Android's recent
  shortcuts on the **app icon long-press** (`ShortcutManagerCompat.pushDynamicShortcut` +
  `reportShortcutUsed`, which lint already notes is missing).
- Tapping one does the action again (a fresh shuffle, with the Shorts choice made the first time, Dewi
  2026-10-14); any can be pinned to keep it.

## Notes for the build

- One sealed "action" type next to `Pin` (or `Pin` gains `ShuffleAll`/`Radio`), played through the same
  path as pins (`PinPlayer`), with the recent list persisted and capped.
- Logged: recorded, de-duplicated, replayed, and why a replay failed.
