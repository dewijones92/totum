---
title: A podcast's show name AND its publisher, everywhere
kind: feature
status: shipped
area: podcasts
updated: 2026-09-21
---

# Both names, never one instead of the other

**Ask (Dewi, 2026-09-21):** *"make sure the podcast title and podcast channel name are visible in
the app wherever they SHOULD be please."*

They were not, and the reason was one line in `DefaultPodcastRepository`:

```kotlin
author = author ?: feedTitle      // the episode's itunes:author BEAT the show's own name
```

An episode's maker line held **either** the episode's `itunes:author` **or** the feed title,
whichever came first — so on any feed that names its network, the show's own name appeared
nowhere in the app: not in the list, not in the queue, not on the player, not in the
notification. On `podcasts.files.bbci.co.uk/p02nrsln.rss` that meant *BBC Radio 5 Live* and never
*Football Daily*.

Both are facts and neither substitutes for the other, so both are carried:

| Field | Is | Shown as |
|---|---|---|
| `MediaItem.author` | The show / channel — **always** the feed title for a podcast | 🎙️ (podcast) / 📺 (video) |
| `MediaItem.publisher` | The network behind it, when the feed names a different one | 🏷️ |

A publisher equal to the show name is **dropped rather than repeated**, case-insensitively: most
feeds set `itunes:author` to the show's own title, and two identical lines say less than one.

One rule, in one place: `publisherFor` in `:core:domain` returns a sealed `PublisherChoice`
(`Named` | `RepeatsShow` | `NotGiven`), and the podcast mapping, the facts seam and the diagnostics
line all call it. It had grown **three** spellings within a day, two trimming and one not, which is
what the review of this change caught. `mediaFacts` still applies it as belt and braces, but the
doc used to claim "two layers of defence" and that was wrong: our own rows can never arrive with a
publisher equal to the author, so the second call can only fire on data this app did not write —
and on the player page it compares against `author = null`, so there it cannot fire at all.

## Where the publisher comes from

`itunes:author` on the **episode** wins, else `itunes:author` on the **channel**. The channel-level
one was not being parsed at all (`ParsedFeed` had no `author`), which is the one place most feeds
actually state their publisher — so the fallback matters more than the primary.

## One field, not a podcast-only column

`publisher` sits on `MediaItem` beside `viewsText` (video-only) and `membersOnly` (video-only): a
nullable field one pillar fills is still one seam, and every surface that renders a maker line
renders this one without knowing which pillar it has.

It is stored on **every** table that describes an item — `queue_items`, `play_history`,
`downloads`, `local_playlist_items`, `podcast_episodes` — because a queued or downloaded episode
has to read like the one in its feed. That is migration **v21 → v22**, purely additive and
nullable, and it is deliberately the same shape as v18/v19, which were the same defect: a fact that
survived the media session and then died in the database, so the same item read differently
depending on which list you reached it from.

Through playback it rides the session's `albumArtist`, like the title and artist ride theirs, so
`PlaybackState.publisher` cannot go stale against what is actually playing. The system notification
reads title + artist, so the lock screen is unchanged.

## Where it shows

Every list (feeds, podcasts, queue, history, playlists, Library, notifications, channel) via
`mediaItemFacts`, and the full player via `mediaFacts` — which omits the author there, because the
artist line is directly above it, but keeps the publisher, which appears nowhere else on the page.

**The show's own page too**, since round one of the gauntlet pointed out that the one screen whose
whole job is to say what a show IS named the show alone while every row beneath it named the
network. That needed the fact on the source as well as its items: `MediaSource.PodcastFeed.publisher`
and a `podcast_feeds.publisher` column. Its subscription chips are left as titles — a chip has no
room for a second line and is a way to navigate, not the show's page.

**Search's podcast hits** rendered two bare `Text`s of their own: both names visible, but the second
unlabelled while the video hit one row below wore 📺, and with no dedup, so a self-published show
whose iTunes `artistName` equals its `collectionName` printed the same name twice. They go through
`mediaFacts` now.

## Tests

- `DefaultPodcastRepositoryTest` — the show always owns the maker line; the episode's author beats
  the channel's; a channel-level author becomes the publisher every episode shares; a publisher
  that repeats the show is dropped whatever its case. The pre-existing assertion that pinned
  `author ?: feedTitle` was **seen to fail** on the new code and rewritten to the new rule.
- `RssParserTest` — the channel's `itunes:author` is read; a feed with none says null rather than
  borrowing its own title.
- `MediaItemSubtitleTest` — the two lines and their order, the label glyph differing from the mic,
  the case-insensitive drop, a blank publisher leaving no bare label, a video never growing a
  publisher line, and the player page keeping it while dropping the author.

## A refresh could not teach an existing subscription anything

Found by reading the database after a refresh rather than by reading the code, and it is the sharpest
lesson of this change: `toMediaSource` was reached **only by `subscribe`**, so `refreshFeed` re-saved
the source it had just read from storage. Every episode row carried the network's name while
`podcast_feeds.publisher` stayed NULL for ever and the show's page had nothing to name — and the
episode rows looking right is exactly what made it invisible. The source is now rebuilt from the
parse, keeping the original `subscribedAt` so refreshing still does not reorder feeds.

A side effect, stated because it is a behaviour change nobody asked for: a feed that **renames
itself** now takes its new name on refresh. Previously a subscription kept the title it had on the
day you subscribed, for ever.

Verified on the emulator against the real BBC feed: every episode row reads 🎙️ Football Daily /
🏷️ BBC Radio 5 Live / 📅 18 hours ago; the show's page header reads Football Daily with 🏷️ BBC Radio
5 Live under it; `SELECT title, publisher FROM podcast_feeds` returns `Football Daily|BBC Radio 5
Live`; and the log line reads `names show="Football Daily" channelAuthor=BBC Radio 5 Live
episodes=273 shown=273 droppedAsRepeatOfTheShow=0 named-nobody=0`.
