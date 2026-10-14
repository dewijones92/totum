---
title: Recent actions come back as a Recently row and app-icon shortcuts
kind: adr
status: accepted
updated: 2026-10-14
---

# 36. Recent actions come back as a Recently row and app-icon shortcuts

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"if I shuffle all songs of artistX does that appear as a recent action … as a shortcut
somewhere???"*. It did not: pins are manual. He chose a Recently row on the Music home and in Library, and
the app icon's long-press shortcuts.

## Decision

- `RecentActions` (app `pins/`) keeps the last 8 actions, newest first, one per key, persisted like pins.
  Recorded by the code that performs them: `ShuffleAll` (a new `Pin.ShuffleAllOf` holding its sources and
  the Shorts choice), `MusicRadio.start` (`Pin.Radio`) and the album page's Play and Shuffle (`Pin.Album`,
  now with `shuffled`).
- A recent action is a `Pin`, so it plays through `PinPlayer` (a shuffle-all re-walks its sources and
  shuffles afresh), can be pinned from its long-press menu, and opens from `totum://pin/<key>`.
- App-icon shortcuts: the two newest actions, then the pins, up to the launcher's room
  (`appIconShortcuts`), with `reportShortcutUsed` for the newest.

## Consequences

- One action type for pins and recents; nothing played twice by two paths.
- Logged under `[recent]`: recorded (new or moved up) and the app-icon set published.
