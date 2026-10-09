---
title: A Music tab takes Search's place in the bar; search is an action in every header
kind: adr
status: accepted
updated: 2026-10-09
---

# 24. A Music tab takes Search's place in the bar; search is an action in every header

- Status: Accepted
- Date: 2026-10-09

## Context

Dewi, 2026-10-09, with a screenshot of a YouTube album playlist: *"i want a nice way in the app to
play/search music/albums etc"*. Asked where music should live, he chose **its own tab**. The bar already
had five destinations (Videos · Podcasts · Queue · Search · Library), Material's ceiling, so a sixth
needed something to give; of the options put to him he chose **Search moves to a top-bar icon**.

The app has no top app bars. Every tab draws its own header through one of two shared composables,
`ScreenHeader` (Videos, Podcasts, Library, Music) and `CollapsingTitle` (Queue).

## Decision

- The bar is Videos · Podcasts · **Music** · Queue · Library (`TopLevelDestination`).
- `SearchAction`, a 🔍 icon, is appended by `ScreenHeader` and `CollapsingTitle` themselves, read from a
  CompositionLocal (`LocalOpenSearch`) that the shell provides. No tab wires it, and a header outside the
  shell (previews, tests) simply shows none.
- Search opens as an overlay **inside the tab area** (`TabNavigation`, `TabOverlays`), so the dock and the
  mini player stay; Back closes it, and choosing a tab closes it. Album and artist pages stack over the tab
  the same way (`LocalOpenMusicPage`), Back popping one at a time.
- The Music tab is the **same** `SearchViewModel` with `SearchScope.MUSIC`, which asks songs, albums and
  artists and leaves podcasts, videos and torrents `Absent`. Global search is unchanged, keeps its Songs
  section and never asks for albums or artists. With no query the Music tab shows its home instead of
  search history.
- The list filter toggle shows a funnel, not a magnifier: with the search action beside it the Queue header
  otherwise showed two identical icons that did different things.

## Consequences

- One search engine, two scopes; a new music catalogue is a section, not a second screen.
- Search is one tap further from a cold start than a bar destination was.
- The Music tab is YouTube-only by nature (albums, artists and radio exist only on YouTube Music);
  everything beneath it (player, queue, groups, downloads, routing) is the shared seam.
