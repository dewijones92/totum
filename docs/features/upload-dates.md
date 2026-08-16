---
title: Views and dates everywhere — including the video page
kind: feature
status: shipped
area: video/search/playback
updated: 2026-08-15
---

# Views and dates everywhere

The view count and the publication date under every video title, on every surface that lists one —
and, since 2026-08-06, on the video page too.

Dewi, 2026-08-06: *"i want things like videoviews, datestuff, datepublished always visible whenever
videos are listed (test scrolling down also to see if they work for scrolled down of list of
videos). this additional detail must appear within video page also"*.

## One formatter, everywhere — one fact per line

`mediaItemFacts(item)` returns **`[channel, views, date]`** and every list calls it — Videos, Search,
Channel, Related, Playlists, Podcasts, History, Library, Notifications, Queue. Each gets its own
line. Duration is deliberately absent — it rides on the thumbnail, where nothing can truncate it and
it costs no vertical space.

### Why it is a list and not a line (2026-08-15)

It used to return one string, `author · views · date`, rendered at `maxLines = 1`. Dewi: *"the view
count and the date published on YouTube videos sometimes gets hidden, there's like a 3-dot thing. I
want them each to be on a separate line … and they need to be also visible within the video page
itself."*

The facts were being listed and were not visible, which is the letter of the 2026-08-06 request
without the point of it. A channel name of any length pushed the rest past the right-hand edge and
Compose replaced it with an ellipsis — so the two facts he had asked for were the two that
disappeared. Each line still caps at one line, but a line now holds ONE fact, so an ellipsis can
only ever shorten a long channel name.

Returning the *parts* rather than a rendered line is what lets one seam serve both the row and the
video page: whoever renders decides how, and only `mediaFacts` decides what and in what order. The
row also takes `subtitleLines: List<String>`, so a caller with something extra to say appends
another line instead of splicing it into a sentence that then truncates — the Library's file size
was doing exactly that, glued to the end where the ellipsis reached it first.

His layout call, asked and answered rather than assumed: channel, views and date each on their own
line; duration stays on the thumbnail; and it applies to **both pillars**, so a podcast episode
shows two lines rather than three because it has no view count.

Two smaller rules sit behind it, both now unit-tested:

- **`mediaDateText`** prefers the source's own relative wording ("2 days ago") over a formatted
  absolute date. YouTube only gives the wording, and re-deriving it from a timestamp would drift
  from the site.
- **`formatViewCount`** turns a raw number into YouTube's own shape, truncating and never rounding
  up, so a yt-dlp row (which gives a count) and an InnerTube row (which gives text) cannot look like
  different apps in the same list.

Neither `mediaItemFacts` nor `mediaFacts` is `@Composable` any more. Neither ever called
anything composable, and the annotation was the only thing keeping the app's most-seen piece of
formatting out of reach of a JVM unit test.

## Where the data comes from

| Surface | Views | Date | Source |
|---|---|---|---|
| Subscriptions / feeds / Watch Later / History | ✅ | ✅ | `LockupParser`, `VideoTileParser` |
| Channel videos | ✅ | ✅ | InnerTube channel browse |
| Related videos | ✅ | ✅ | `RelatedVideosParser` |
| Search | ✅ | ✅ | `InnerTubeVideoSearchSource` (primary) |
| Search, yt-dlp fallback only | ✅ | ❌ | `extract_flat` entries carry a count but no date |
| Podcast episodes | n/a | ✅ | `RssParser` `publishedAt` |
| **The video page** | ✅ | ✅ | the listing, round-tripped through the session |

## The video page: why it needed more than UI work

It showed only the title and the channel, and no amount of Compose would have changed that, because
**the facts were being destroyed before playback started**.

Resolving a video builds a *fresh* `MediaItem` from what the extractor says — in three separate
places in `VideoResolver` — and an extractor says nothing about view counts and nothing about
publication dates. All three set `publishedAt = null`. So a row reading "1.2M views · 2 days ago"
arrived at the player with both gone, along with the members-only flag and the content kind.

Two changes, both single-seam:

- **`MediaItem.withStreamFrom(stream)`** in `:core:domain` — the rule for which facts belong to a
  resolution and which to the listing that asked for it. Deliberately the *smallest* rule that fixes
  the loss: the resolution wins wherever it actually says something, and the listing fills the
  silence. An earlier version also preferred the listing's *title*, which changed shipped behaviour
  nobody had asked to change; `SearchViewModelTest` failed on it immediately and it was reverted.
- Applied **once**, in `VideoPlaybackLauncher.play`, at the moment of resolution — so every path
  downstream (a quality switch, Listen mode, a stall replay) carries the facts without knowing it
  has to. `play` now takes the listing item rather than a bare URL; there was exactly one production
  caller, and it already held it.

Then `PlaybackState` gained `viewsText`, `publishedText` and `publishedAt`, and `FullPlayer` renders
them through the same `mediaFacts` the rows use — one per line, as of 2026-08-15 — with the author
omitted, because the artist line is directly above.

They are **held on the controller**, exactly as the skip segments, chapters and subtitles already
are, and for exactly the same reason: they do not reliably cross the session. The first version put
them in `MediaMetadata.extras`, which worked locally and then failed *intermittently* on CI — the
view count came back null from the queue's play path in one run and not the next, on a commit that
touched only test files. A `MediaController`'s copy of an item does not dependably carry extras, so
that channel was a race rather than a mechanism, and on a device the page would have shown the
numbers and then dropped them with nothing to explain it. The pattern was already in the file; it
should have been followed the first time.

## Coverage

| Level | What it holds |
|---|---|
| JVM unit | `MediaItemSubtitleTest` — the line's order, the date rule, absence, and that a counted and a quoted view figure render identically |
| JVM unit | `WithStreamFromTest` — which facts a resolution may change, and which it may not |
| JVM unit | `VideosPagingTest` — a **page-2** video keeps its views and date. This is where "scrolled down" can really break: one composable renders every row, so what differs about row 60 is where its data came from |
| Instrumented | `PlayerMetadataTest` — the values come back out of the *real* session, including that absence stays absence, and via the queue's own play path. Run five times over to confirm the earlier intermittency was gone rather than quiet |
| Instrumented | `ScrolledRowMetadataTest` — a row at index 60 of 80, the last row, and a row scrolled back into view, each showing its own numbers and not another's |
| Instrumented | `ItemFactsSurviveStorageTest` — the facts survive the **database**, on the queue and on history, and absence stays absence |

## And then they died in the database (2026-08-07)

The above shipped and CI failed on `playing from the queue carries the listing facts too` — a test
that had passed locally, on timing. It was right and the feature was half-broken.

`PlaylistItemColumns` is the denormalized shape a `PlayableItem` persists as, shared by the queue,
play history, downloads and local playlists. It had no column for a view count or a relative date,
and its shared rebuild set `publishedAt = null`. So the facts survived the media session and then
died on the way to disk: the video page showed them for an item tapped from a feed and showed
nothing for the same item replayed from the queue — which is the ordinary case, and the only case
after a restart.

Exactly the same shape of defect as the resolver one above, in a second place, and found the same
way: by a round-trip test rather than by reading the code. Fixed by migration **v17 → v18**, three
nullable columns on each of the four tables, no backfill possible — nothing can reconstruct them.

`DownloadsMigrationTest` had to change with it: it ran only the v13 → v14 step and then compared
the result against a table Room builds fresh at the *current* version, quietly assuming no later
migration would touch `downloads` again. It now runs every migration from v13 upward, which is the
upgrade a real v13 install actually takes.

**The lesson, since it has now cost twice:** a fact that only a listing knows dies at every rebuild
boundary — resolution, persistence, and the session — and each boundary needs its own round-trip
test. Two of the three were only found because something else failed.

## Superseded

The 2026-07-24 note in this doc's history said search-result dates were **deferred**, because the
yt-dlp `extract_flat` path has no cheap date. That is no longer the gap: search resolves through
`InnerTubeVideoSearchSource` first, which carries `publishedTimeText` and `shortViewCountText`, and
yt-dlp is only the fallback. The stale claim mattered — `docs/todos/_index.md` still listed this as
`planned` while `docs/features/_index.md` said `shipped`, and the two disagreed for weeks. Both are
corrected; treat this area's map as having been unreliable.
