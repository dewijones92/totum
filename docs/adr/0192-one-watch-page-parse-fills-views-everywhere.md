---
title: One watch-page parse fills views, date and description everywhere
kind: adr
status: accepted
updated: 2026-11-27
---

# 192. One watch-page parse fills views, date and description everywhere

- Status: Accepted
- Date: 2026-11-27

## Context

Dewi, from the 23 to 25 November reports (v0.1.944): *"there should be view counts on every item … are there any other
inconsistencies throughout the app? I want it more consistent."* Every list draws its facts through `mediaItemFacts` /
`RowFacts`, so the gap was data, not drawing: views were dropped at the point an item was built. Checked in code:

- Related, Up next and Explore lockups give the count without the word (`["107K", "3d ago"]`), and `looksLikeViews` wanted
  "views", so the count was thrown away.
- `VideoResolver` built its items from yt-dlp (no `view_count` read), from the `/player` response (`videoDetails.viewCount`
  never read) and over SABR, all with no views; `withStreamFrom` did not carry them either. A row from a shared link, a
  notification tap, Send to Pi, Starred or History therefore had none.
- The WEB `/next` response, which three places already read separately for the date or description
  (`RelatedVideosParser` made three JSON parses of one body, `PublishDays`, `SignedInTvPlayer`), also carries
  `videoViewCountRenderer.shortViewCount`.
- 233 of 234 `shared` queue rows lacked views, in a queue of about 6,000 rows.
- `ChannelFeedParser` skipped `<media:statistics views="N"/>`. Checked against the real feed in
  `youtube-channel-feed.xml`: RSS does carry views. [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)'s
  exception saying it does not was wrong.

## Decision

- **One read of the watch page.** `WatchNextParser.facts(body)` parses the body once and returns `WatchNextFacts`
  (description, `publishedOn`, `publishedAgo`, `viewsText`); `details()` is built on it. `RelatedVideosParser`,
  `PublishDays` and the signed-in TV description all use it, and the separate `description(body)` and `publishedOn(body)`
  reads are gone. Views are YouTube's own short text ("24K views"); only a long count ("24,123 views") is shortened,
  through the one `formatViewCount`, which moved to `:lib:common` (`ViewCountText.kt`) so `:lib:innertube` can use it
  (it was in `:core:domain`).
- **A bare compact count beside the date is the view count** (`LockupParser`): `["107K", "3d ago"]` reads "107K views", in
  the same row as a date part only, so a channel called "2025" is never read as one, and it is never the author line.
- **Views through the resolver.** `PlayerDetails.viewsText` (exact `videoDetails.viewCount`, else the watch page's),
  `MediaMetadata.viewCount` (yt-dlp), carried into all three resolver items, and `withStreamFrom` fills a row that has none
  (the listing's own still wins). `fillingSilenceFrom` already did.
- **Stored rows learn them lazily** (`SharedMetadataRepair`, `Need.VIEWS`): the queue rows around what plays (5 before, 30
  after) and every download that lack views. The lookup is the same `/next` read as the date (`factsOf`), so a row that
  lacks both costs one request, not two. It stays ADR 171's: batches of 20 a second apart, off the main thread, at most
  two tries, one `pass:` line per pass (now with how many dates and views the watch page gave). On Dewi's queue that is
  36 requests a start-up, not 6,000. Songs get their plays this way too (a radio or Discover row had none); never a date.
- **RSS rows read `<media:statistics views>`** with `formatViewCount` (group newest items, New uploads, Long ones' pool).
  RSS still carries no duration.
- **`[facts]` says which path** a row came from: a row entering the queue with no views is logged with its source
  (`shared`, `ytfeed:…`, `music:radio:…`), as the undated one already was.
- **A day-only date never says hours** (`PublishedAge.text`). A date that is only a day (`ofDay`, noon UTC) reads
  "Today", "Yesterday" or "23 Nov" ("23 Nov 2025" in another year), judged against the viewer's own day. The watch page's
  day is Pacific time read as UTC, which made a video a few hours old say "18 hours ago". A time with a real time of day is
  still "2 hours ago". Where the same read also gave YouTube's wording (`relativeDateText`), the repair uses
  `PublishedAge.best`, which prefers the wording while it is under two days old. A day-only date is recognised by being
  exactly 12:00:00 UTC; a real timestamp at that exact second would read as a day, a one in 86,400 miss that cannot say
  anything false about the day.
- **Group notifications judge new by video id** (`GroupNews`): an item is announced if its id was not announced before, it
  was published after its member joined the group (or the group was first looked at), and within 48 hours of the check.
  Opening a group no longer makes the next check skip what was published before it was opened; the 🆕 badge still clears on
  opening. `settle` logs how many it read, announced and did not, and why (already announced, no date, before the group,
  older than 48 h, notifications off, could not be posted). `"group"` joined `Diag.KEPT_TAGS`, so those lines outlive a flood
  of playback lines.
- **Discover reaches past what you have heard** (`DiscoverWalk`): when the near artists are all skipped as heard, up to
  three of their pages are read only to reach their similar artists (hop two), offering no song from them. A seed that lists
  nobody (a composer) falls back to the other artists credited on the seed song, then to that song's own radio, and says
  so. A step that finds nothing says why, and the "📭" toast carries it.
- **A blank session title or picture falls back to the queue row's** (mini and full player, `facedFrom`), and the blank is
  logged; the Media3 item's title and art are logged when a next item is armed and when it crossed over.
- **`playing.route` was unknown because the reader took "play or pause asked by…" for a play line.** It now matches
  `play <id> from …` and the playing item's own id, and the route is recorded when decided (`PlayRoutes`, 64 items, also at
  a gapless crossover, which writes no play line) so the trail scrolling cannot hide it.
- **The New uploads page says what it is**: "New uploads" with "📥 The newest from every channel you subscribe to, not one
  group", after a report of a user who thought it was a group's list. Its list is the first page of the account's
  subscriptions feed and ends there; it logs how many rows it opened with.

## Recorded exceptions

- **The All subscriptions page's latest-upload line has no views.** It is one line from a Room table of title, author,
  picture and date; adding a column is a schema bump for a line that shows no facts block.
- **Starred, playlist and History rows keep the views of the row they were saved from.** The lazy repair covers the queue
  and downloads only; a row saved with none gets them when it is played (`withStreamFrom`) or queued.
- **A live stream's "watching" count is not a view count** and is not filled from the watch page.
- **The one-in-86,400 noon rule** above.

## Consequences

- One parse of a watch page serves the Related tab, the group day lookup and the signed-in description; a fourth reader
  has `facts()` to call rather than a fourth walk of the tree.
- A row with no views costs at most one watch-page request, near what plays or on a download, twice at most; none
  outside that window.
- ADR 108's RSS exception is corrected there; ADR 95, 167 and 65 are amended where they changed.
- Tests: `LockupParserTest` (bare count, never the author; seen failing first), `TheWatchPageCountsTheViewsTest`,
  `ViewCountTextTest`, `WithStreamFromTest`, `ChannelFeedParserTest`, `SharedMetadataRepairTest` (views with the date from
  one read, a date-holder asked for views alone, songs never a date, Dewi's 5,857 rows ask 36), `PublishedAgeTest`,
  `GroupNewsTest`, `DiscoverWalkTest`, `MusicDiscoverTest`, `FacedFromRowTest`, `PathTakenTest`, `PlayRoutesTest`.
