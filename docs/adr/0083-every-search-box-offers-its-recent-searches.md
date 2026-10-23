---
title: Every search box offers its recent searches
kind: adr
status: accepted
updated: 2026-10-23
---

# 83. Every search box offers its recent searches

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-22: *"every search in the app must contain recently searched"*. Only the main search (and the Music
search, which shares its store) offered them; the filter on every list, a channel's search and the transcript search
did not.

## Decision

- One seam: `SearchHistoryStore`, now scoped. `AppContainer.recentSearches(scope)` gives one store per kind of search
  box (`SharedPrefsSearchHistoryStore` with a scope, a key of its own in the same prefs file, 8 kept), provided to the
  UI as `LocalRecentSearches`; `rememberRecentSearches`, `RecentSearchChips` and `KeepWhenSettled` in
  `ui/common/RecentSearches.kt` are all a search box needs.
- Scopes are per **kind** of box, not per screen instance: `filter:<kind>` (the list filter's place up to its first
  space, so every channel shares `filter:channel` and every podcast page `filter:podcast`), `channel-search` and
  `transcript`. The main search keeps its own unscoped list, unchanged.
- A box with a live filter has no submit, so a query is kept when it has stood unchanged for 2.5 s **and found
  something**, or when the keyboard's search key is pressed; under two characters is never kept. Half-typed words do
  not stand still long enough to be kept.
- While the box is empty its recents show under it as chips that wrap: tap to search again, ✕ to forget one.

## Consequences

- Review of 2026-10-23: a channel search counts as having found something only once its own results have arrived
  (`TabState.foundSomething`: not while the previous query's results are still showing during the load); each box
  subscribes to its store once, not on every keystroke.

- Recents are not part of the Drive backup (neither was the main search's list).
- Tests: `RecentSearchesTest` (instrumented: a settled filter is kept and offered again, a tap fills the box; a
  half-typed one is not kept; another kind of list has its own), red first; `SearchHistoryStoreTest` unchanged.
