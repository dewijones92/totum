---
title: Fuzzy filter on every list
kind: feature
status: shipped
area: ui
updated: 2026-09-24
---

# Fuzzy filter on every list

Dewi, 2026-09-24: *"put fuzzy search everywhere there's a list"*.

## The seam

- **`FuzzyMatch` / `fuzzyFiltered`** (`:core:domain`) — one pure rule for every list. Case, accents and
  punctuation ignored (`Gerónimo` = `geronimo`, `AC/DC` = `acdc`). **Every** query word must be found, in
  any order, in any field. A word is found when it is:
  - inside a field (3+ letters) — so "ighto" finds Brighton as you type;
  - the START of a word (1–2 letters) — "ai" finds "AI news", not "tr**ai**ler" (seen on the emulator);
  - an abbreviation, letters in order from the first (3+ letters) — "fbl" finds Football;
  - a typo: one edit for 4–7 letters, two for 8+, transpositions counted as one — "brigton", "tenis".
  - **The list keeps its own order** (queue order, newest first, …); the filter only hides.
- **`MediaItem.searchableText`** = title, maker, publisher; **`MediaSource.searchableText`** = name,
  publisher. Every screen filters media by the same fields.
- **UI** (`ui/common/ListFilter.kt`): `FilterField` (search icon, clear, "N of M"), `FilterableList`
  (field + list + "Nothing here matches"), and `LazyListScope.filterField` for lists whose header lives
  inside the `LazyColumn`. Queries survive rotation (`rememberSaveable`) and each list has its own.

## Where

Videos feed · Podcasts (episodes) · a podcast's page · a channel's Videos / Shorts / Search / Playlists
tabs · Queue · History · Library downloads · local playlists (list and page) · account playlists (list
and page) · New uploads · All subscriptions · Diagnostics (tags and messages) · the add-to-playlist and
groups pickers.

Deliberately not: **Search** (it is already a search box) and the player's **Related** strip.

## Two rules that are not obvious

- **A paged list does not page while filtered.** A short filtered list is "scrolled to the end", which
  would trigger load-more over and over. Paging resumes when the filter is cleared.
- **The queue, filtered, is a plain list with no drag handles**, each row keeping its REAL queue index:
  play, move to top/bottom and remove act on the right entry. Dragging within a subset has no meaning.

## Diagnostics

`[filter] <place> "<query>" shows N of M`, once the query has been stable for 0.8 s.

## Tests

`FuzzyMatchTest` (JVM; typo, abbreviation and short-token rules each mutation-checked);
`ListFilterTest` (instrumented): narrowing and clearing, no-matches, **a filtered paged feed does not
page** (red without the gate), **a filtered queue removes and moves the right entry** (red with filtered
positions). Verified by hand on `totum-api35`: "tenis" → 59 of 1,601 subscriptions, "brigton" → 2 of 274
episodes, "ai" → 8 of 66 (20, mostly noise, before the short-token rule), queue move-to-top checked in
`queue_items`.
