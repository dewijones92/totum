---
title: BBC programmes are followable sources whose episodes open in iPlayer
kind: adr
status: accepted
updated: 2026-11-20
---

# 184. BBC programmes are followable sources whose episodes open in iPlayer

- Status: Accepted
- Date: 2026-11-20
- Builds on: [ADR 105](0105-audiobooks-are-books-from-the-archive-and-torrents.md) (a book is a podcast feed read by its
  own parser), [ADR 154](0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) and
  [ADR 176](0176-on-this-phone-is-a-source-behind-the-one-player.md) (sources that are shown differently but travel
  through the one seam), [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md) (recorded exceptions)

## Context

Dewi wants to follow BBC programmes ("Have I Got News for You") like his YouTube channels and podcasts, see "🆕" when an
episode arrives, and tap it to open the BBC iPlayer app. The agreed exception: **a BBC episode opens in iPlayer instead
of playing in Totum.** Totum does not download BBC video, does not scrape it and does not try to play it: iPlayer streams
are the BBC's, DRM-wrapped and licence-bound, and only the public metadata is used.

Checked live on 2026-11-20 (no key, no sign-in):

- `GET https://ibl.api.bbci.co.uk/ibl/v1/programmes/<pid>/episodes?rights=web` lists a programme's episodes: 20 a page by
  default, `per_page=100` works (59 episodes in one 160 KB answer for Have I Got News for You, `b006mkw3`), and the
  answer's `programme` object names the show, its channel (`master_brand`) and its image. An unknown or expired programme
  answers `200` with `"programme": null` and no episodes.
- `GET https://ibl.api.bbci.co.uk/ibl/v1/new-search?q=<words>&rights=web&per_page=<n>` searches. Results are mixed:
  `type: "programme"` (a brand or series, with an episode `count`) and `type: "episode"` (one-offs and clips with a
  `tleo_id`). Only the programmes can be followed.
- An episode has `versions[].availability.end` (when it leaves iPlayer; some expire in 14 days, some run for over a year),
  `versions[].duration.value` (ISO 8601), `first_broadcast_date_time`, `subtitle` ("Series 72: Episode 6") and `synopses`.
  Image URLs carry a `{recipe}` placeholder (`640x360` works).
- `https://www.bbc.co.uk/iplayer/episode/<pid>` is the episode's public link; it answers `301` to a slugged address, and
  the iPlayer app claims it as a link.

## Decision

### One source seam: a programme is a podcast feed

- **A BBC programme is a `MediaSource.PodcastFeed` whose address is the iBL episodes URL**
  (`BbcProgrammes.feedUrl(pid)`, `per_page=50`), read by `IblEpisodesParser` in `DefaultPodcastRepository` exactly as a
  book is read by `ArchiveItemParser` (ADR 105). Subscribing, refreshing, the Podcasts tab, groups and their news,
  tab badges, new-content notifications, pins, the follow mark, Not for me and backup all work through the existing path
  with no new code, because they are already written against `PodcastFeed` and `MediaItem`.
- **No new `MediaKind` and no new `MediaSource` variant.** `MediaSource` is sealed with about 75 uses and `MediaKind` is
  matched in 35 exhaustive `when`s that decide how a thing is *played and tinted* (channel or podcast, player tabs,
  transcript source, download strategy). A BBC episode has none of those concerns: it is listed like an episode and never
  enters the player. A third pillar would have meant 35 branches saying "not applicable", and every real branch it needed
  already exists on `PodcastFeed`. What differs is **how an item is played**, so the new variant lives where that is
  decided.

### One play seam: an external handle and an external route

- **`PlayHandle.External(url)`** is the new handle and **`PlayRoute.External(playable, url)`** the new route. `routeNow`
  answers it first, before any copy on disk, the network, Listen mode or a copy at home, so none of those can ever win.
  Every `when` over a handle or a route is exhaustive, so the compiler found each place that had to decide
  (`PreloadUrl`, `NextUpPrefetcher`, `readyAgain`, the Pi's `HomeDownloads` and `SoundFiles`, `QueueArming`,
  `PlaybackQueue.route`, `describe`).
- **A `MediaItem` is external when its `mediaUrl` is an iPlayer episode link** (`IPlayer.isEpisode`, one rule in
  `:core:domain`); `toPlayableOrNull` then builds the external handle, so every way in (tap, notification, group page,
  search, Library, history, Star) gets it without knowing. The handle persists as `EXTERNAL` with its address in the
  existing handle column.
- **`PlayableItem.fetchUrl` is null for it**, so nothing can try to download it; the row hides ⬇️, and the long-press menu
  drops Play next, Add to queue, Peek, Listen/Watch and Download.
- **The queue opens it and never holds it.** `PlaybackQueue` takes an `openExternally` function (the app wires
  `ExternalAppOpener`). `playNow`, `peek` and `playAll` open an external item when **you** asked (`byYou`); an automatic
  play never opens another app. `enqueue`, `playNext`, `enqueueAll`, `playNextAll`, `addToGroup` and `playAll` leave
  external items out and log how many (`dewidebug … open in another app and are never queued`), so a "📥 New" group or a
  mixed list can never put iPlayer in the middle of a queue. `route` also handles `PlayRoute.External`, so the `when` is
  total.
- **Opening is an intent**, tried twice and logged: `ACTION_VIEW` of the episode link restricted to
  `uk.co.bbc.iplayer`, then the same link unrestricted, which the browser takes. A toast says which happened
  (`📺 Opening in BBC iPlayer…`, `🌐 BBC iPlayer isn't installed, so this opens in your browser…`, or `❌ Nothing on this
  phone could open that link.`). A group-news notification for a BBC episode opens the episode in iPlayer
  directly (as one for a YouTube video opens the video); the podcast-episode alerts open the app, as for any podcast.

### Facts and presentation

- **`MediaItem.availableUntil`** (`Instant?`) is the one new fact, stored in `podcast_episodes.availableUntilEpochMs`
  (**Room v29**, `ALTER TABLE … ADD COLUMN`, null for every old row). Rows say `⏳ On iPlayer until 22 Dec 2027`, with
  `· ⏰ 9 days left` inside two weeks, `· ⏰ less than a day left` on the last day and `⌛ No longer on iPlayer (it ended on
  …)` after it; the details sheet carries the same line. The facts are computed from the stored end date every time a row
  is drawn, so they age.
- An episode's **title is its subtitle** ("Series 72: Episode 6", else the title), its maker line is the programme and its
  publisher the channel (BBC One); its date is the first broadcast, its length the version's, its description the long
  synopsis, its id `bbc:<pid>`. Rows wear `Shown.BBC` (📺, "BBC iPlayer"), chosen from the id like Jellyfin and torrents.
- **Search has a 📺 BBC tab** (`SearchTab.BBC`, `SearchHit.Programme`, `BbcSearchSource`): only `type: "programme"` results
  are kept, each with its channel, episode count and category, and each opens the programme's page and follows like a
  podcast row (`asPodcast()` hands it to the one row and the one subscribe path). The page is the podcast page: episodes
  with their dates and availability, a ➕ follow button, no Shuffle all and no Inbox toggle (both feed a queue that cannot
  hold these).

### Politeness and logging

- **Only the public metadata API, asked as little as possible.** One request per programme page or refresh (`per_page=50`)
  and one per search. A `TimedCacheFetcher` in front of the shared text fetcher keeps BBC answers for **15 minutes** (40
  entries, oldest out first, failures never kept), so reopening a page, a refresh and a group's news inside a quarter of an
  hour cost nothing. Nothing polls: followed programmes refresh when the existing podcast refresh does and when the existing
  new-uploads job and group news run, at their existing cadence.
- Logged: every search (`bbc` — query, programme count, how many episodes and clips were left out), every cache hit and
  miss with running counts, every parse failure with its reason, every open (`external` — the title, the link and whether
  the iPlayer app, the browser or nothing took it) and every item left out of a queue.

## Recorded exceptions (also in ADR 108)

- **A BBC episode opens in iPlayer; it does not play in Totum.** It has no Play next, Add to queue, Peek, Download or
  Listen/Watch, never enters the queue, has no Play on… (a device cannot play it) and no stats of its own. Star, Add to
  playlist, Share, Not for me, Details, the group menu and the follow mark work as for any episode; playing a list that holds
  one skips it (logged) and plays the rest.
- **A BBC programme page has no Shuffle all and no Inbox toggle**, because both exist to fill the queue.
- **The OPML export leaves BBC programmes out**: their address is a JSON API, not an RSS feed another app could read. They
  are still in the Drive backup.
- **Starred and playlist copies of a BBC episode do not carry its end date** (the item columns of those tables have no such
  field); only the programme page, group lists and the details sheet say when it leaves iPlayer.

## Consequences

- New episodes are found by the ordinary refresh, so a followed programme's "🆕" depends on how often podcasts refresh;
  the BBC cache can delay a manual refresh by up to 15 minutes. An episode iPlayer has removed disappears from the next
  refresh's answer and is replaced like any dropped episode; until then its date says it has gone.
- **Not built:** BBC Sounds radio (a different API), `tleo` episodes and clips from search (a programme page is the unit),
  more than the newest 50 episodes of a programme, and any playback.
- **Checked on `totum-api35` on 2026-11-20** (debug build over a real v28 database, which migrated to v29 in place): searching
  "have i got news" showed a 📺 BBC tab with 4 programmes; the Have I Got News for You page listed 50 episodes with
  "⏳ On iPlayer until 22 Dec 2027" and the first-broadcast age; ➕ Subscribe stored the feed and 50 episodes with their end
  dates; tapping an episode logged `iPlayer app not installed or not handling it; opened in the browser instead`, toasted
  "🌐 BBC iPlayer isn't installed, so this opens in your browser…" and brought Chrome to the front; the queue stayed as it was
  (one unrelated item); the long-press menu offered Add to playlist, Star, Not for me, Share, Copy link, Pin, Add to home screen,
  Details, Add show to group and Mark as played, and no queue, Peek, download or Play on… entries.
- **Not verifiable here:** that the iPlayer app itself takes the link. The emulator has no iPlayer, so only the "not installed,
  so the browser" half ran. The link is the one iPlayer publishes for sharing; the app is tried first, restricted to
  `uk.co.bbc.iplayer`.
- Tests: `BbcProgrammesTest` (9, over two trimmed real answers in `core/data/src/test/resources/bbc/`),
  `TimedCacheFetcherTest` (5), `OpmlExporterTest` (+1), `DefaultPodcastRepositoryTest` (+2), `IPlayerEpisodesTest` (9),
  `AnIPlayerEpisodeOpensElsewhereAndNeverQueuesTest` (6), `IPlayerEpisodesAreShownTest` (7); device:
  `RoomSubscriptionStoreTest` (+1), `DownloadsMigrationTest` (+1).
