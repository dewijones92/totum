---
title: Search results arrive per section
kind: feature
status: shipped
area: search
updated: 2026-11-20
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
- The tab row is `WrappingTabs` (`ui/common`, was `ScrollableTabs`), extracted from the player's tabs, which use it too; when the labels do not fit one line it wraps onto a second rather than scrolling with a label cut at the edge.

Log lines (`search`): `tabs: VIDEOS=40+ TORRENTS=3 searching AUDIOBOOKS=? searching ...; showing VIDEOS (chosen=nothing
yet)` whenever a section answers, and `tab A -> B` on a tap.

**Tests still to write:** `searchTabsFor` and `defaultSearchTab` (absent sections, partial counts, failed and searching
tabs, the default rule); a Compose test for the tab row, the dot and the empty line; instrumented
`SongSearchSectionTest.songsSitAboveTheVideoResults` is obsolete (songs and videos are on different tabs now) and
`SearchSectionStatesTest`/`AudiobookSectionsTest` should drive `SearchContent` through a tab tap.

## Paging: whole pages, one state (2026-11-20)

Report logs on v0.1.927 showed Videos stuck at `8+` however far the list was scrolled, and `VIDEOS=16+` followed at once by
`VIDEOS=8+`. Two bugs, both fixed with a test seen failing first:

- **Every page was cut to eight.** `RESULTS_PER_SECTION = 8` was passed as `limit`, and `HttpYouTubeSearch.toPage` and
  `toAccountPage` did `take(limit)`, so an anonymous WEB search (about 19 a page) kept 8 and the continuation then skipped
  the other 11. The search now returns the whole page and the limit is not applied to it; the list shows everything
  fetched. `WholePageTest` (the captured WEB sample, which holds more than 8, asked at limit 8, and the captured TV sample asked at limit 1).
- **Load more was overwritten by the next section to answer.** `loadMoreVideos` wrote the grown list to `results`, but
  `searchStream` kept its own `state` flow, so a late section (torrents finishing) re-sent the older state and the list
  went back to one page; `LoadMoreOnScrollToEnd` then saw shown 8 and asked at 8 and stopped for good. `moreMusic` had the
  same clobber. Load more, the music shelves and every section now write to ONE `MutableStateFlow` (`loaded`) that the
  stream publishes from. `SearchViewModelTest`: a slow podcast section answering after load more, and after more albums,
  leaves 16 videos and 15 albums.

Log: `[search] next page -> N returned, T total (more=…)` and `more <shelf> -> N returned, T in all`.

## 📥 Yours: what you already have (2026-11-20, ADR 183)

Dewi, 2026-11-20: *"I want to only see the ones I've downloaded"* (Spinal Tap II was at home and in Jellyfin, not on the
phone). A first tab, **📥 Yours**, lists what the query matches among the phone's finished downloads (📱), the Pi's
finished jobs (🏠) and the Jellyfin library (🎞️), each row saying where it is and playing through the queue's `routeNow`.

| Piece | Where |
|---|---|
| The source | `YoursSearchSource` (`:core:data`): one `SearchSource` whose `updates` flow emits the phone's matches first, then each place as it answers; `YoursMerge` makes one row per thing |
| The hit | `SearchHit.YoursHit`: `Yours(playable, places)` (`YourPlace` PHONE / HOME / JELLYFIN) or `YoursSeries(id, title, year, poster)` |
| The Pi | `GET /jellyfin/browse?search=<term>` (films, series and episodes, 50 at most) echoing the term in `JellyfinListing.search`; `JellyfinApi.search` |
| The tab and rows | `SearchTab.YOURS` first; `YoursSection.kt`; a playable is `MediaItemRow` with a "📱 On this phone · 🏠 At home · 🎞️ In Jellyfin" line; a series is a "📺 Bluey" row that opens the Jellyfin page at that series over the results |

- Shown as the tab on screen whenever it has matches; while it has none and is still asking it has no tab (no flicker);
  with no matches but a note ("🏠 Home not reachable") it keeps a tab so an empty answer is not read as "not downloaded".
- `SearchOutcome.Success` and `SearchSection.Found` carry `notes`, lines that are not results.
- Not searched: the queue (6,033 rows on Dewi's phone) and pins; the Music scope.
- A series row (2026-11-20) opens `JellyfinScreen(openAt = id)` as an overlay of search (back returns to the results); an episode row, from here or anywhere else, plays as its whole series from that episode ([ADR 154](../adr/0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) amendment). Needs the Pi redeployed for the series rows.
- Logs (`search`): `dewidebug yours "<q>": N of M download(s) match`, `… finished download(s) at home match` or why not read,
  `… jellyfin N playable of M returned (total T), S series` or why not, and `… done after Tms: phone=… home=… jellyfin=… series=… -> R row(s)`. Playing an episode logs `[queue] dewidebug series: <id> (SxxEyy) plays alone: <why>` when it does not carry on, and `[jellyfin] … queued with its series "<name>": N episode(s)` when it does.
- Checked on the emulator against the real Pi (not yet deployed with the route): "spinal" listed the Pi's Spinal Tap II
  files at home with the poster, and the note "⚠️ The home server needs updating to search Jellyfin".

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

## 📺 BBC (2026-11-20)

A BBC tab (Everything scope) holds `SearchHit.Programme` results from the iPlayer metadata API, one request per query, programmes
only. Each row is the podcast row (open the programme's page, ➕ follow). See [bbc-programmes.md](bbc-programmes.md).
