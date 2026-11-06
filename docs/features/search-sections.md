---
title: Search results arrive per section
kind: feature
status: shipped
area: search
updated: 2026-11-06
---

# Search results arrive per section

Dewi, 2026-08-07: *"the search in the app is quite slow as its blocked by the torrent search??????
come up with sensible ui/ux"*.

## What was wrong

Not concurrency — all three sources were already started together:

```kotlin
val podcasts = async { … }; val videos = async { … }; val torrents = async { … }
toLoaded(podcasts.await(), videos.await(), torrents.await())
```

That last line threw the concurrency away. Nothing reached the screen until the **slowest** source
finished, and the slowest is always the torrent search: it goes out to Prowlarr and through
FlareSolverr, which is seconds at best, and off the home network the request does not fail fast — it
hangs until it times out. So a YouTube search that had answered in a moment sat behind it with
nowhere to be shown, and the whole feature felt broken.

## The shape that fixes it

A section has a **lifecycle**, which the old model could not express. It was a list plus a `failed`
boolean per section, so "nothing yet" and "nothing found" were the same value — and if you cannot
say "still looking", you cannot show partial results without lying about the rest.

`SearchSection<T>` in `:core:data` names all four states, once, for every section:

| State | Means | Screen shows |
|---|---|---|
| `Searching` | still out | the heading, with a slim bar under it |
| `Found` | it answered (possibly with nothing) | its rows, or nothing at all when empty |
| `Failed` | it could not answer, and why | the heading and a reason |
| `Absent` | this section does not exist here | nothing — no home server, no heading |

`Absent` earns its place: someone who has never set up a home server would otherwise see a torrent
section permanently reporting a problem they do not have.

`SearchViewModel.searchStream` emits once with everything `Searching` and again each time a source
answers, updating only its own slot. `channelFlow` carries the emissions from three sibling
coroutines and closes when they finish; `transformLatest` upstream cancels the lot when the query
changes, so a search nobody is waiting for stops making requests.

## UI

One renderer, `hitSection`, draws all three — the same reason there is one `SearchSource` and one
`SearchHit`. A fourth section would render correctly without anything new being written.

- **A waiting section keeps its heading.** Seeing "From your home server" with a quiet bar under it
  says more is coming; an absent heading would say that is everything.
- **The indicator is deliberately slim**, not a spinner or a skeleton. Results above and below it are
  already usable, so it must read as "more may arrive", not "the screen is busy".
- **An empty answer shows nothing at all.** With three sections landing separately, empty headings
  would be on screen for most of a search rather than none of it.
- **The home server gets its own failure wording** — it is only reachable at home or on the VPN, so
  the usual cause is where you are, not a fault to go looking for.

Each section is also bounded at 20 seconds. Generous, because a slow answer is still worth having
once the rest is on screen; the bound only exists so a section cannot spin for ever.

## Results as tabs (2026-11-06)

Report 20261101T232057: with every section stacked, a search for a film put torrents, audiobooks, podcasts, songs and
videos in one long scroll. The results are now one tab per kind, over the same sections
([todo](../todos/search-results-tabs.md)):

| Tab | Section functions it draws | Count |
|---|---|---|
| Videos | `hitSection` over `results.videos` | fetched so far; `40+` while the section has another page |
| Torrents | `torrentSection` | all the home server returned; `N+` while an indexer is still being asked |
| Audiobooks | `bookSections` (free books and book torrents merged) | the merged list; `N+` while either is still out |
| Podcasts | `hitSection` over `results.podcasts` | the directory's answer |
| Songs | `songSection` | fetched so far; `N+` when YouTube Music has more |
| Albums and artists | `musicCollectionSections` | both shelves added; `N+` when either has more |

- A tab exists only when its section does (`searchTabsFor`): no home server means no Torrents tab; the Music tab
  shows Songs and Albums and artists only.
- A small dot after a tab's name means that section is still out (a section that has not answered, or a torrent
  search still asking an indexer); a count appears as soon as the section answers. A failed section keeps its tab and
  its own explanation.
- Until the person taps a tab, the tab shown is the first one in the order above that has results (else the first
  failed or still-searching one), so a slow tab never steals the screen from one that is already listed; a tap pins
  the choice, and a chosen tab that disappears falls back to that rule.
- Each tab keeps its own scroll position. **Videos still load more as the list scrolls to its end** (the trigger is
  only armed while that tab is showing); the music "more" buttons and the torrent "show more" button are unchanged.
- An answered tab with nothing in it says `📭 No videos for “query”` rather than showing a blank list.
- The tab row is `ScrollableTabs` (`ui/common`), extracted from the player's tabs, which use it too.

Log lines (`search`): `tabs: VIDEOS=40+ TORRENTS=3 searching AUDIOBOOKS=? searching ...; showing VIDEOS (chosen=nothing
yet)` whenever a section answers, and `tab A -> B` on a tap.

**Tests still to write:** `searchTabsFor` and `defaultSearchTab` (absent sections, partial counts, failed and searching
tabs, the default rule); a Compose test for the tab row, the dot and the empty line; instrumented
`SongSearchSectionTest.songsSitAboveTheVideoResults` is obsolete (songs and videos are on different tabs now) and
`SearchSectionStatesTest`/`AudiobookSectionsTest` should drive `SearchContent` through a tab tap.

## Diagnostics

Per section, with its own timing: `"ceuta" torrents after 8412ms -> 12`. "Search was slow" could
never say *which* source was slow, which is the only question worth asking about it.

## Coverage

| Level | What it holds |
|---|---|
| JVM unit | `SearchStreamsPerSectionTest` — videos on screen while other sources are still out; a slow source not holding back a fast one; sections settling independently; one failure marking only its own section; the torrent section absent with no home server; **Dewi's exact case** (home server configured, torrents slow, YouTube already listed); and an unanswered section ending `Failed` rather than spinning |
| Instrumented | `SearchSectionStatesTest` — what each of the four states looks like: a waiting section keeps its heading, an empty one disappears, a failing one explains itself, and the home server's failure names the network rather than a fault |

Four of the seven JVM cases were watched failing against the old await-everything shape.

The tests hold the clock deliberately (`advanceTimeBy` past the debounce, then `runCurrent`) rather
than `advanceUntilIdle`, which would run virtual time past the section timeout and report every
source as unanswered before the test could answer for it.
