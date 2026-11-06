---
title: Queued tracks keep their artist
kind: todo
status: built (tests owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Queued tracks keep their artist

Found 2026-11-06 by the ✨ Ask spike on Dewi's Full backup: 1,965 of 5,838 queue items have no `author`, about 420
of them Chopin album tracks, so neither the rows nor Ask can say who plays them. Against ADR 108's "every path that
builds an item fills what is known". Decided: find the queueing paths that drop the artist (album and artist pages,
radio, shuffle all, pins), fill it in, and repair existing rows once (from the album, the source or a lookup), with a
test per path seen red first.

Whole-batch review: the one-off repair extends `SharedMetadataRepair` (already retries titles and dates); the fill goes into the queue entry seam, not each caller.

## Built 2026-11-06 (code first; tests owed)

**Cause found:** `MusicPageParser.album` gave every track the album's artist *id* and artists list but never its artist
*name*, and an album track's own row carries no artist text (its second column is plays). So every album track, from
the album page, artist shuffle, pins and the radio from an album, queued with `author = null`. The artist page's top songs
had the same fallback missing. Both now fall back to the page's own name (`song.artist ?: strapline`, `?: name`).

**Repair of rows already stored:** `SharedMetadataRepair` gained a third need, the artist, for music rows (kind MUSIC or
a `music:` source) with no author, in the queue and in the downloads. `ArtistLookup` reads each album once per session
(album id from the row's `AlbumRef`, else its `music:album:` source) and takes the track's artist, else the album's;
a `music:artist:` source takes the artist page's name; two tries with the same back-off as dates. The fill goes through
the existing `queue.adoptFacts` and `downloads.learnFacts` (`fillingSilenceFrom`), which only fill silence.

**Not found:** the backup was not available to the builder, so the other ~1,500 author-less rows are unexplained (torrent
files and book chapters carry no artist by nature; videos should have a channel). Check by source after the repair runs.

Tests owed: `MusicPageParserTest` (album track with no artist text takes the album's artist, seen red; artist page top
songs); `SharedMetadataRepairTest` (an album track with no author learns it with one album read for many tracks, a track
the album does not list takes the album artist, a failed lookup retries twice then stops); `ArtistLookupTest`.
