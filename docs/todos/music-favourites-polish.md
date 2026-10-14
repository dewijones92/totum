---
title: Music favourites polish — a cover for Liked songs, and Like on album track rows
kind: todo
status: done 2026-10-14 (cover, track-row hearts); player Like merge open
area: music
priority: low
requested: 2026-10-14
updated: 2026-10-14
---

# Music favourites polish

Two follow-ups seen on `totum-api35` after [ADR 32](../adr/0032-music-favourites-live-on-the-account.md)
shipped (screenshot of the Music home, 2026-10-14):

1. **Liked songs shows a blank image placeholder.** `likedMusic()` in `ui/music/MusicScreen.kt` builds the
   playlist with `thumbnailUrl = null`, so `PlaylistRow` draws the generic picture icon beside
   "❤️ Liked songs". Give it a drawn cover instead (a heart on the brand's tangerine, from `theme/Color.kt`,
   not a new colour), through the same row so nothing else changes. No network image: YouTube's own
   Liked Music cover is per-account and not in the `VLLM` header the app already reads.
2. **Album track rows have no Like.** Liking a song today means long-pressing it for the menu
   (`SongLikeAction`), which reads the song's rating from the account when the menu opens. On an album
   page Dewi may want to like several tracks in a row. Options to put to him before building, because
   each costs something:
   - a ♡ on every track row: needs every row's rating, i.e. one `next` call per track (12–30 calls per
     album), or reading the whole liked list (1,206 songs on his account, about 80 pages);
   - keep the menu but make it faster: no extra calls, one more tap per song;
   - a ♡ on the playing track only, in the full player, which already exists.
   Recommend: keep the menu, and ask him whether the per-row heart is worth the calls.

## Done when

The Liked songs row shows the heart cover on the emulator (screenshot), and the track-row question is
answered and recorded here.

## 2026-10-14

1. Done: `HeartCover` in `ui/music/MusicCollectionRows.kt` (the hero tangerine, `colorScheme.primary`, with
   a filled heart), passed to `PlaylistRow` as a `cover`; screenshotted on `totum-api35`.
2. Dewi chose the heart on every track row (2026-10-14); built as `TrackLikeHeart` on the album page.
3. From the batch review (2026-10-14): the player's Like (`WatchViewModel.setRating`, optimistic, no
   read-back) and the song menu's Like (`MusicFavourites.setSongLiked`, read back, refreshes the count) are
   two paths for one write. A like in the player never updates the shelf's count. Route the player through
   `MusicFavourites`.
