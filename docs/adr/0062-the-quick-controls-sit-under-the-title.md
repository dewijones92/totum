---
title: The quick controls sit under the title
kind: adr
status: accepted
updated: 2026-10-16
---

# 62. The quick controls sit under the title

- Status: Accepted
- Date: 2026-10-16

## Context

Listen/Watch, speed, volume boost, skip silences and the sleep timer were tiles in the grid below the tabs, so changing
any of them meant scrolling down the player page. Dewi: "I want them to be more easily accessible beneath a video …
currently I have to scroll down the page which is really annoying". He chose a strip under the title, above the tabs,
holding those five, and removing them from the grid.

## Decision

- `PlayerQuickStrip` (`PlayerHeader.kt`, tagged `player-quick-strip`) sits after the title and transport, before the
  watch actions: a wrapping row of compact chips, each "icon · name · value" (`Speed · 1.5×`).
- The chips are the same composables as the tiles (`ListenWatchToggle`, `SpeedTile`, `BoostTile`, `SkipSilenceTile`,
  `SleepTimerControl`) drawn compact through `LocalCompactTiles`, so a control is defined once and its menu, state and
  logging are the tile's. Each takes a `shortLabel`; the accessible label stays the long one.
- The grid (`SecondaryControls`) keeps autoplay, repeat and fast start.

## Consequences

- `PlayerKeepsEveryControlTest` asserts the five are in the strip and nowhere else, so neither a duplicate nor a lost
  control can return unnoticed.
- The strip wraps, it does not scroll: at a large font size it takes two lines rather than hiding a chip off the edge.
