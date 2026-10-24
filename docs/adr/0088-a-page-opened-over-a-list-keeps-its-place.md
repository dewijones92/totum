---
title: A page opened over a list keeps the list's place
kind: adr
status: accepted
updated: 2026-10-24
---

# 88. A page opened over a list keeps the list's place

- Status: Accepted
- Date: 2026-10-24

## Context

Report 0.1.709 (2026-10-24): "I came up with the search results then I went go to channel on one video and when I
went back the search results had scroll back at the top … in general through the app … I want it to remember where
I was". Search showed the channel by returning early in place of its results, so the results list left composition
and came back at the top; the same shape was in Podcasts, Settings, Library, Stats, the Videos overlay (a playlist
over a channel) and the music page stack, and a channel's tabs lost each other's scroll. Tabs already kept their
place (July, a `SaveableStateHolder` per tab), and the Videos feed had its own holder.

## Decision

- `ListThenDetail(detail, listKey, onBack, list, shown)` (`ui/common/ListThenDetail.kt`) is the one way a page opens
  over a list: while `detail` is null it shows `list` inside a `SaveableStateProvider`, so its saveable state (the
  scroll, above all) waits for it; `onBack`, when given, makes system back close the page. Used by Search (results,
  and the channel under a playlist), Podcasts, Settings, Library (home and each page), Stats and the Videos overlay.
- `KeepsEachPlace(key)` keeps one place per key where the content swaps in place (a channel's tabs); the music page
  stack keeps one per page, with its holder outside `key(page)`.
- Search and Settings had no system back for their pages (back closed the whole search, or jumped to the Library
  home); they now pass `onBack`.
- Logged: `[place] <list> kept while a page is open over it`, `[nav] back closes the page over <list>`.

## Consequences

- Only saveable state survives (`rememberSaveable`, `rememberLazyListState`); state held in plain `remember` is
  rebuilt, and effects in the list run again when it comes back.
- Not covered: leaving picture-in-picture rebuilds the whole shell (`AppShell` returns early while floating), and the
  player's tabs (the transcript follows the spoken line anyway).
- Test: `ListThenDetailTest` (instrumented) — scrolled to row 40, a page opened and closed, back at row 40 (row 0
  before); system back closes the page and keeps the place. Checked on the emulator: search, scroll to the videos,
  go to a channel, back: the same rows on screen.
