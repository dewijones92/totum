---
title: Shuffle everything a channel (or podcast) has ever published
kind: todo
status: refining — questions for Dewi below
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

## Questions for Dewi

1. ❓ **Shorts**: included, or the Videos tab only? (Proposed: Videos only.)
2. ❓ **Already watched**: skip finished videos/episodes, or shuffle everything? (Proposed: skip
   finished, keep half-watched.)
3. ❓ **Where it goes**: insert after what's playing (like album shuffle, keeps your queue), or replace
   the queue?
4. ❓ **Huge channels**: put the whole channel in, or cap it (e.g. 500 random picks), given the queue
   is already 269 items on your phone?
5. ❓ Also on **podcasts** by default (the Unified rule says yes), and on **channel groups**?
