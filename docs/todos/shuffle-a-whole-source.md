---
title: Shuffle everything a channel (or podcast) has ever published
kind: todo
status: built 2026-10-12 — see features/shuffle-all.md and ADR 30
area: queue/channel
priority: medium
requested: 2026-10-12
updated: 2026-10-12
---

# Shuffle a whole channel or podcast

Dewi, 2026-10-12: *"I want the ability to shuffle ALL videos of a channel"*.

## What exists (read from the code, `main` at `0c96adda`)

- **Channel page** (`ui/channel/ChannelScreen.kt`, `ChannelViewModel.kt`): tabs Videos, Shorts,
  Playlists and Search. The header has Subscribe, groups and a filter. There is no play-all or shuffle.
  Multi-select gives Play next and Add to queue for the rows you pick.
- **Paging**: `loadVideos()` fetches the first InnerTube page; later pages arrive one at a time as you
  scroll (`loadMore` / `pageMore`, `BrowseTarget.Continuation`). **No load-every-page loop exists.**
  A channel reached by handle falls back to yt-dlp, which returns the whole list in one go.
- **Podcast feed page** (`PodcastFeedScreen.kt`): no play-all or shuffle either. An RSS feed is already
  the whole list (`Page.last`), so only the channel side needs paging.
- **Shuffle precedent**: `MusicAlbumViewModel.shuffle()` does `queue.playAll(tracks.shuffled(), group)`.
  `PlaybackQueue.playAll(items, group)` is pillar-neutral and inserts a tagged run **after** the current
  item; it never replaces the queue. ADR 27 puts album and artist shuffle on their own pages, and the
  Queue's shuffle is "up next" only.

## Proposed shape (to confirm)

One seam for both pillars, per the Unified law: **"every item a source has"**, a `Flow` of pages that
ends at the last one.

- For a channel it walks the InnerTube continuations to the end. For a podcast it is the one feed.
- A **🔀 Shuffle all** button in the header of both the channel page and the podcast page. It loads
  every page with a visible count ("loading 340 of …"), then `playAll(shuffled, QueueGroup(source))`,
  like an album shuffle.
- The first track can start as soon as the first page arrives, with the rest shuffled in as it lands,
  so a 3,000-video channel does not mean a long wait. To confirm: does the start then lean towards
  recent uploads?
- Logged under `[shuffle]`: the source, pages fetched, items, what was excluded and why, time taken.
- Tests: the page walk against a fake channel with three pages and a broken continuation; exclusions;
  a podcast going through the same seam.

## Decided (2026-10-12, Dewi)

1. **Shorts: ask each time.** When the channel has Shorts, Shuffle all pops up "Include Shorts?".
2. **Watched items are not skipped**: everything is shuffled.
3. **It lands as its own group**, a `QueueGroup` for the source (as album shuffle does), so it can be
   collapsed, skipped or removed as one, without disturbing the rest of the queue. Dewi floated "a
   separate queue thing". A group gives that without a second queue model; revisit if it does not
   feel separate enough.
4. **No cap**: the whole channel goes in, however large. Start playing from the first page; the rest
   streams in as pages load (with a visible count).
5. Podcasts: **yes**, by the Unified rule (not answered explicitly).
6. **Groups: yes** (Dewi). Shuffle all on a group walks every source in it (channels and podcasts), then
   shuffles them all together into one queue group. The Shorts question is asked once for the whole group.
