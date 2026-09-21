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
feeds set `itunes:author` to the show's own title, and two identical lines say less than one. The
comparison happens twice on purpose — at the mapping, and again in `mediaFacts` — so a row stored
before v22, or any other source that fills both fields alike, cannot show one name twice either.

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

Every list (feeds, podcasts, search, queue, history, playlists, Library, notifications, channel) via
`mediaItemFacts`, and the full player via `mediaFacts` — which omits the author there, because the
artist line is directly above it, but keeps the publisher, which appears nowhere else on the page.

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

Verified on the emulator against the real BBC feed: every episode row reads 🎙️ Football Daily /
🏷️ BBC Radio 5 Live / 📅 17 hours ago, and the mini player names the show under the episode title.
