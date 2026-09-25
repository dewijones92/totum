---
title: Fuzzy filter on every list
kind: feature
status: shipped
area: ui
updated: 2026-09-25
---

# Fuzzy filter on every list

Dewi, 2026-09-24: *"put fuzzy search everywhere there's a list"*.

## Where the field lives (2026-09-25)

Every list's field is **hidden behind a ⌕ toggle** in its header, Dewi's pick in the overhaul: the tabs
(Videos, Podcasts, Queue, Library downloads), the sub-screens (history, new uploads, both playlist lists,
a playlist, all subscriptions, diagnostics) and the source pages (a podcast, each channel tab, with the
toggle following the tab you are on). It opens with the keyboard up, stays open while it holds text, and
clearing it closes it. `ListFilter.open` is saved with the query, so rotation keeps it.

- **The keyboard comes up once, when you open it** — not every time the field scrolls back into view.
  The first version asked for focus from the field itself, so scrolling a feed away and back, or
  switching tabs, popped the keyboard again (the review caught it; `ListFilterTest` pins it, and fails at
  that step with the old code).
- **The toggle stays while the field is open**, even when the list empties under it, so an open field can
  always be closed. It hides only when there is nothing to filter and the field is shut.
- **A queue emptied while filtered comes back unfiltered**: the queue's filter is keyed on the queue being
  empty, as it was when the filter lived inside the non-empty branch.
- **Pickers keep the field open** (add to playlist, groups): in a picker, finding the entry is the task.

## The seam

- **`FuzzyMatch` / `fuzzyFiltered`** (`:core:domain`) — one pure rule for every list. Case, accents and
  punctuation ignored (`Gerónimo` = `geronimo`, `AC/DC` = `acdc`). **Every** query word must be found, in
  any order, in any field. A word is found when it is:
  - inside a field (3+ letters) — so "ighto" finds Brighton as you type. Words run together match only
    from the start of a word ("acdc" finds "AC/DC"; "heart" does not find "t**he art**");
  - the START of a word (1–2 letters) — "ai" finds "AI news", not "tr**ai**ler" (seen on the emulator) —
    except in scripts written without spaces (Chinese, Japanese, Korean, Thai), where it matches anywhere;
  - an abbreviation: 4+ letters, **no vowels**, in order from the first letter — "ftbl", "cmptr", "brgtn";
  - a typo, first letter right: one edit from 5 letters, two from 8. A 5-letter word may be one typo from
    a whole word ("tenis") or one missing/extra letter from the start of one ("briig"); from 6 letters any
    edit counts while typing. Words containing digits never match by typo ("2024" ≠ "2025").
  - `ß` reads as `ss`; a query of only symbols filters nothing; `y` counts as a vowel.
  - Letters and digits written together or apart find each other ("gpt6" ↔ "GPT-6", "2024" ↔ "#Euro2024")
    from the start of a word or a letter/digit change, never mid-number ("234" does not find "1234").
  - Chinese/Japanese/Korean/Thai query words match anywhere; Latin ones never match inside them.
  - **The list keeps its own order** (queue order, newest first, …); the filter only hides.
- **`MediaItem.searchableText`** = title, maker, publisher; **`MediaSource.searchableText`** = name,
  publisher. Every screen filters media by the same fields.
- **UI** (`ui/common/ListFilter.kt`): `FilterField` (search icon, "Filter N items", clear, "N of M" inside
  the box so it never adds a line), `FilterableList`
  (field + list + "Nothing here matches"), and `LazyListScope.filterField` for lists whose header lives
  inside the `LazyColumn`. Queries survive rotation (`rememberSaveable`) and each list has its own.

## Where

Videos feed · Podcasts (episodes) · a podcast's page · a channel's Videos / Shorts / Playlists
tabs · Queue · History · Library downloads · local playlists (list and page) · account playlists (list
and page) · New uploads · All subscriptions · Diagnostics (tags and messages) · the add-to-playlist and
groups pickers.

Deliberately not: **Search** (it is already a search box) and the player's **Related** strip.

## Second review, same day — fixed

A test typing "gama" stopped matching after the vowel rule and failed CI (the instrumented suite was not
re-run after the last matcher change — now it is, in full, at CI's geometry); digit words were barred from
joined text; Latin tokens matched inside Japanese; Korean syllables decomposed into letters (NFC restored);
Diagnostics logged two contradictory lines (now `diagnostics vitals` / `diagnostics events`); a paused
list logged "no more to fetch" on every keystroke (now silent, the filter line says it is paused); a false
"cleared" line on switching source; a stale count after the list changed under a steady query; the
podcast page lost its query when the media filter emptied it; a dialog's "no matches" could squeeze the
create field (checked on the emulator: it does not, now it cannot).

**The filter bar pushed rows off a small screen.** CI's emulator is 320×640 dp at 160 dpi, and
`QueueGroupCollapseTest` failed there only (it passed at 1080×2400 and at 1080×1920 @ 480). The count
line moved inside the field; the whole instrumented suite (156 tests) now passes at `wm size 320x640`,
`wm density 160`.

## Two rules that are not obvious

- **A paged list does not page while filtered.** A short filtered list is "scrolled to the end", which
  would trigger load-more over and over. Paging resumes when the filter is cleared.
- **The queue, filtered, is a plain list with no drag handles**, each row keeping its REAL queue index:
  play, move to top/bottom and remove act on the right entry. Dragging within a subset has no meaning.

## Speed

Each list's text is prepared once per list change, **and only once something is typed** (`ListFilter.filter`
holds it lazily), and the query once per keystroke. Until 2026-09-25 an untouched filter prepared every
row on every list change, which on the Subscriptions list during a channel check meant 1,600 sources
re-normalised on the main thread about 32 times; `ListFilterTest` now asserts an untouched filter reads
no row at all. Measured on the laptop JVM, warm: 1,600 items prepared in ~13 ms; **< 1 ms per keystroke
for 1,600 sources, ~4 ms for 5,000 diagnostics events**. A phone is several times slower, which is still
inside a frame for every list but possibly Diagnostics.

## Diagnostics

`[filter] <place> "<query>" shows N of M[, paging paused until cleared]` once the query has been stable
for 0.8 s, and `[filter] <place> cleared, all M shown`. Places name the source (`channel <title> videos`,
`podcast page <title>`, `account-playlist <title>`); the Videos place trail carries `filter="…"`.

## Independent review, 2026-09-24 — fixed

Too-loose typos ("live"→"like", "news"→"new", "2024"→"2025", "chess"→"cheese"); an extra letter
mid-word failing while typing ("briig"); Chinese/Japanese two-character words failing; 3-letter
abbreviations and joined-word matches adding noise ("cat"→"Create", "heart"→"The Art"); per-keystroke
normalisation of every field; a query following you to a different channel or podcast (now keyed on the
source); clears and paused paging unlogged; the channel Search tab getting a second box (removed); the
count line appearing on the first keystroke and shifting the list (now always shown); an empty groups
picker showing a filter; pull-to-refresh dead on "no matches".

## Tests

`FuzzyMatchTest` (JVM; typo, abbreviation and short-token rules each mutation-checked);
`ListFilterTest` (instrumented): narrowing and clearing, no-matches, **a filtered paged feed does not
page** (red without the gate), **a filtered queue removes and moves the right entry** (red with filtered
positions). Verified by hand on `totum-api35`: "tenis" → 59 of 1,601 subscriptions, "brigton" → 2 of 274
episodes, "ai" → 8 of 66 (20, mostly noise, before the short-token rule), queue move-to-top checked in
`queue_items`.
