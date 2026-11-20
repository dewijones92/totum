---
title: Search shows what you already have, first, in a 📥 Yours tab
kind: adr
status: accepted
updated: 2026-11-20
---

# 183. Search shows what you already have, first, in a 📥 Yours tab

- Status: Accepted
- Date: 2026-11-20
- Builds on: [ADR 110](0110-downloads-at-home-the-pi-fetches-with-the-apps-own-code.md) (downloads at home),
  [ADR 154](0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) (Jellyfin through the Pi),
  [ADR 136](0136-offline-playable-is-whatever-routenow-plays-from-the-device.md) (what plays offline)

## Context

Dewi, 2026-11-20 17:29: *"I am trying to search for my film that I downloaded. we downloaded spinal tap didn't we? but
there's no easy way to … filter by like oh, these are things I've downloaded. I want to only see the ones I've
downloaded."* The film was downloaded at home and filed into Jellyfin's films. It was not on the phone and not in the
queue, and a search for "spinal tap" showed only YouTube, torrents, podcasts and songs. Every source search knew about
was somewhere he does not own; the places he does own (phone downloads, the Pi's finished downloads, the Jellyfin
library) were not asked.

## Decision

- **One more search section, `SearchHit.Yours`, behind the one `SearchSource` seam**, drawn as the first tab
  (📥 Yours, with a count) and shown as the tab on screen whenever it has matches (the tab order is the existing
  "first tab with results" rule). It is an `Everything`-scope section only; the Music scope does not get it (see
  Consequences).
- **Three places, one row.** `YoursSearchSource` (`:core:data`) matches the query, with the existing `FuzzyMatch` (the
  list filter's matching) over `MediaItem.searchableText`, against:
  - the phone's finished downloads (`DownloadManager.observeDownloaded`), 📱;
  - the Pi's finished jobs (`HomeDownloads.jobsRead`, which the app already polls; read once with a refresh only when it
    has never been read), 🏠;
  - the Jellyfin library, 🎞️, asked on the Pi: `GET /jellyfin/browse?search=<term>` returns films and episodes whose name
    matches (`SearchTerm`, 50 at most), so the phone never downloads the library (1,611 videos on 2026-10-31).
- **Each row says where it is** ("📱 On this phone · 🏠 At home · 🎞️ In Jellyfin") and is the shared `MediaItemRow`, so
  the long-press menu, play state and status chips are the app's. It plays through `PlaybackQueue.playNow`, so
  `routeNow` picks a disk copy first, then the home copy (`HomeCopyFirst`'s `atHome`), then the Jellyfin stream.
- **The same thing in two places is one row.** The same item id on the phone and at home is one row in both places.
  A film that was downloaded at home and then filed into Jellyfin is two ids (`torrent:…` and `jellyfin:…`) for one file,
  so a Jellyfin film merges with a phone or home item **only when its title matches (the same `FuzzyMatch`) and its year
  appears in the other's name** (an episode: the series name and the same `SxxEyy`, read with `episodeCodeOf`); no year,
  no merge. A phone copy keeps its own row and gains 🎞️ (it still plays offline); a home-only item is replaced by the
  Jellyfin row (a poster and a tidy title) which also says 🏠. Rows are ordered phone, home, Jellyfin, then by title.
- **Progressive, and honest about the Pi.** The phone's matches arrive first (the section is `Found` with "still waiting
  for 🏠 home, 🎞️ Jellyfin"); the Pi's join them. A Pi that does not answer in 10 s, or fails to connect, adds the line
  "🏠 Home not reachable" and the phone's matches stay. A Pi that answers a search with no echo of the term (the route
  is not deployed yet) adds "⚠️ The home server needs updating to search Jellyfin" and lists nothing from it, because an
  old Pi would otherwise answer with its top-level library. A Pi with no Jellyfin (503) says nothing. The tab shows for
  a note even with no matches, so an empty answer is not mistaken for "not downloaded".
- **Not included: the queue and pinned items.** Dewi's queue holds 6,033 items (2026-11-20 state dump), nearly all YouTube
  links that are not held in any sense; listing the matches would bury the film under the very results he was escaping.
  Pins are shortcuts to channels and albums, not playable files. Both can be added as a fourth place later (one more
  `YourPlace` and one more input) if he asks.
- **Logging.** One `dewidebug yours` line per source (matches of how many, or why it was not read) and one summary line
  per query with the counts, the time and any notes; the section's tab line carries the notes.

## The list headers' "Downloaded" filter

`ItemFilters.DownloadedFilter.DOWNLOADED` means "a finished copy on this phone" (`state is DownloadState.Downloaded`), and
`PLAYS_OFFLINE` means "plays with no signal" ([ADR 136](0136-offline-playable-is-whatever-routenow-plays-from-the-device.md)).
**Recommendation: it should not count "at home".** A home copy is not on the phone, does not play offline, and the
Library's Downloads page already separates "📱 On the phone" from "🏠 At home". If a filter for the Pi's copies is wanted
on other lists it should be its own value (🏠 At home), fed from `HomeDownloads.jobs`. Not changed here.

## Consequences

- **Deploy order: any.** The phone needs the Pi's new `search` parameter for the 🎞️ part only; without it the phone says
  so and still lists the phone's and home's matches. The Pi's `totum serve` needs a deploy for Jellyfin results.
- The tab and section render through the existing `hitSection`/`WrappingTabs`; `SearchOutcome.Success` and
  `SearchSection.Found` gained `notes` (a line that is not a result), which no other section uses yet. `SearchSection.map`
  now keeps `stillWaitingFor` and `notes` (it dropped `stillWaitingFor`; the only caller, videos, never had one).
- Series are not searched: a series is a folder, and a search row has nowhere to open it. A series' episodes are found
  when their own names match.
- The Music scope's search (songs, albums, artists) has no Yours tab, so a downloaded song is not offered there. That is
  an inconsistency to decide on, not a decision.
- A Jellyfin episode played from a search row plays alone, not as the rest of its season (the Library page's behaviour).
- At Dewi's scale (578 downloads in the 2026-11-20 dump, a few hundred finished home jobs) matching is a few
  milliseconds on `Dispatchers.Default`; `YoursSearchSourceTest` holds 600 + 400 items under a second and capped at 200 rows.
