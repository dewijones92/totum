---
title: Search history
kind: feature
status: shipped
area: search
updated: 2026-11-13
---

# Search history

Recent search queries offered again in the search screen's idle state, and (since 2026-10-23,
[ADR 83](../adr/0083-every-search-box-offers-its-recent-searches.md)) as chips under every other search box: the
filter on every list, a channel's search and the transcript search, each kind with its own list.

## Seam

`SearchHistoryStore` (port, `:core:data` `data/search/`) — one store for the
single unified search box (both pillars), most-recent-first, de-duplicated
case-insensitively, capped at 10.

- Fake: `InMemorySearchHistoryStore` (`:core:data`, tests/previews).
- Impl: `SharedPrefsSearchHistoryStore` (`:app` `search/`), newline-separated
  (queries are single-line), held in a `StateFlow` so the UI observes changes.
- Wired in `AppContainer.searchHistoryStore` (the main search) and `AppContainer.recentSearches(scope)` (one store
  per kind of box), handed to the UI as `LocalRecentSearches` (`ui/common/RecentSearches.kt`).

## Behaviour

- `SearchViewModel` records a query on **explicit submit** (search button / IME
  action / tapping a history item) — not on every keystroke.
- Idle state shows "Recent searches" (tap to re-run, X to forget one, Clear all)
  instead of the empty prompt once there's history.
- Forgetting one (the ✕ or a long-press, here and in every filter, channel, transcript and Ask box) offers Undo on the
  shell's snackbar and puts the query back in its slot (`SearchHistoryStore.restore(HistorySlot)`,
  `rememberForgetRecent`).
- Clear all offers Undo too (2026-11-13): `SearchViewModel.clearHistory` returns every cleared slot,
  `restoreHistory(slots)` puts them back in their old order (`rememberClearRecent`, "🧹 Cleared N recent searches").

## Files

- `core/data/.../data/search/SearchHistoryStore.kt`, `fake/InMemorySearchHistoryStore.kt`
- `app/.../search/SharedPrefsSearchHistoryStore.kt`
- `app/.../ui/search/SearchViewModel.kt`, `SearchScreen.kt`

## Tests

`SearchHistoryStoreTest` (order / dedup / cap / remove / clear); `SearchViewModelTest` (clear all hands back every slot,
restoring them keeps the old order, an empty history offers nothing); `RecentSearchesTest` (instrumented: the other
boxes keep a settled query, offer it again, ignore half-typed ones, and keep one list per kind).

Logs: `[recent] <scope>: kept "<q>" (<why>)`, `picked "<q>" from N recent`, `forgot "<q>"`. Verified
on-device: submit → clear box → query appears under Recent searches.

## Tests owed (2026-11-06 consistency sweep)

`SearchHistoryStore.restore` / `withRestored` (slot, cap, case-insensitive duplicate); Undo after ✕ in search and in a
filter box (instrumented).
