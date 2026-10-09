---
title: YouTube Music
kind: feature
status: shipped
area: music
updated: 2026-10-09
---

# YouTube Music: a Music tab, albums, artists, radio and your library

**Stages 2 and 3, 2026-10-09** (Dewi: *"i want a nice way in the app to play/search music/albums"*).
The decisions are ADRs [24](../adr/0024-music-tab-and-search-in-every-header.md),
[25](../adr/0025-music-plays-as-sound.md), [26](../adr/0026-albums-artists-and-the-youtube-music-library.md)
and [27](../adr/0027-radio-repeat-and-shuffle-are-queue-features.md); this page is the map.

| What | Where | Notes |
|---|---|---|
| Music tab | `ui/music/MusicScreen.kt` | `SearchViewModel` in `SearchScope.MUSIC`: Songs, Albums, Artists. Idle: songs played here, then your YouTube Music recent albums, albums, playlists and artists (signed in) |
| Search everywhere | `ui/common/ShellNavigation.kt`, `ui/TabNavigation.kt` | 🔍 in every tab header; opens over the tab with the dock visible |
| Album page | `ui/music/MusicAlbumScreen.kt` | Play / Shuffle insert after what is playing as a group; Add to end; Download (audio); Radio; "N of M songs on this phone" |
| Artist page | `ui/music/MusicArtistScreen.kt` | Top songs, albums, singles, fans also like; Shuffle and Mix are YouTube Music's own stations |
| Radio | `music/MusicRadio.kt` | Tops itself up from the continuation when ≤3 left; `[radio]` log lines say every decision |
| Plays as sound | `queue/PictureChoices.kt` | `MediaContentKind.MUSIC`; Watch asks for the picture |
| Shared album links | `SharedLink.kt`, `MainActivity` | `list=OLAK5uy_…` opens the album; a song inside one also plays |
| Repeat, shuffle up next | `playback/AutoAdvancer.kt`, `QueueScreen` | For both pillars |
| Catalogue and library | `:lib:innertube/music` | `HttpYouTubeMusicCatalogue` (WEB_REMIX, anonymous), `HttpYouTubeMusicLibrary` (TV client, signed in) |

Limits that remain: the radio is not persisted (after a restart its queued songs stay but it no longer
tops up); a library artist has no channel id, so its page is found by name; the follow mark (ADR 22) shows
on music rows too, where "subscribed" means the uploading channel; YouTube changes these renderers without
notice.

---

# Stage one (2026-08-11): Songs, in the search you already use

Dewi, 2026-08-11, pointing at [OuterTune](https://github.com/OuterTune/OuterTune): *"can we
implement something like outer tune in our app but using our app fundamentals — I wanna be able to
stream YouTube Music in my app"*.

**Stage one, shipped:** a Songs section in search, above the video results, playing through the
player that already exists. Album pages, artist pages, radio and a signed-in library are Stage 2
and 3 and are **not** built.

## Why it was a day and not a fortnight

YouTube Music **is** InnerTube. Same API, same `/player`, same streams — a different client context
and different browse renderers. So a song is **a YouTube video with music metadata**, and that one
sentence is the whole design:

| | Video search | Music search |
|---|---|---|
| Client | `WEB` on youtube.com | `WEB_REMIX` on music.youtube.com |
| Renderer | `videoRenderer` | `musicResponsiveListItemRenderer` |
| Knows | uploader, view count, upload date | **artist, album, exact duration** |
| Plays via | `PlayHandle.Video` | **the same** `PlayHandle.Video` |

Everything downstream is untouched: extraction, the language rules from 0.1.376, stream picking,
the queue, downloads, offline routing, speed, boost, the notification. Proven rather than assumed —
a real YouTube Music song id (`BNMKGYiJpvg`, *Feeling Good*) resolved and streamed as **opus format
251** through the CLI with no music-specific code in the path at all.

`SearchHit.Song` is **not a fourth pillar**, for the reason the torrent case already documents: it
resolves to an ordinary video, so once playing nothing can tell. What earns it its own variant is
only what it *knows* — a row showing "Nina Simone" where the album belongs is a worse row.

## The parsing, which is the actual work

The second column of a music row is a `•`-separated list, and **its shape depends on the request**.
Verified against the live API on 2026-08-11:

```
songs filter:  ["Nina Simone", " • ", "I Put A Spell On You", " • ", "2:54"]
no filter:     ["Video", " • ", "M M P F", " • ", "2.6M views", " • ", "2:58"]
```

So it is read by **shape, not position**: the duration is the segment that looks like a clock, a
leading type word is dropped, counts and years are dropped, and what remains is artist then album.
Reading `segments[0]` as the artist credited a quarter of the results to "Video".

**The songs filter earns its place.** Unfiltered, the music endpoint answers with a mixed bag —
measured: 4 videos, 3 albums, 3 artists, 3 playlists, 3 podcasts and **5 songs**. With the filter:
twenty songs, each with an artist, an album and an exact duration. The filter is opaque protobuf,
so it is pinned as a constant and verified live rather than trusted.

Rows with no `watchEndpoint` — albums, artists, playlists — are **dropped**. Returning them as
songs would put rows in the list that do nothing when tapped.

## Decisions

- **No fallback source.** yt-dlp has no music catalogue to fall back to, and a section saying it
  failed beats one quietly filled with ordinary video results.
- **Songs above videos.** A music query is answered better by YouTube Music than by video search;
  putting the better answer second makes the worse one look like the answer.
- **The artist becomes the item's author, not the whole subtitle.** The row shows "artist • album",
  but an author of "Nina Simone • I Put A Spell On You" would read badly in the queue, the
  notification and the lock screen.
- **Its own source id** (`search:ad-hoc-song`), so history and play state can tell a song apart
  from a video of the same thing. The item id still matches, because it is the same YouTube id.

## Honest limits

- **It is not royalty-free.** YouTube Music's catalogue is overwhelmingly licensed commercial music.
  This is the same posture the app already has for YouTube video — ToS-violating, no Play Store —
  not a new one, and worth saying plainly rather than leaving implied.
- **Premium-only tracks will not stream.** No token this app can hold opens them.
- **OuterTune was read for API shapes only.** It is GPL-3.0 and archived; Totum has no licence file.
  No code was copied and none should be.
- ~~**No sign-in, so no library or playlists**, and no album/artist pages or radio. Stage 2 and 3.~~
  Built on 2026-10-09; see the top of this page. The library turned out to need no new sign-in: the TV
  client answers it with the existing token.
- **The renderers change without notice.** That is what `LiveMusicSearchTest` is for, and it cannot
  run in CI.

## Tests

| Level | Where | What |
|---|---|---|
| unit | `lib/innertube/…/MusicSearchParserTest` | 14 cases against a **real captured response**, plus the shapes it does not contain |
| unit | `core/data/…/InnerTubeMusicSearchSourceTest` | the mapping, the subtitle, and a failure staying a failure |
| unit | `app/…/SearchStreamsPerSectionTest` | the songs section settling independently of the others |
| instrumented | `app/…/SongSearchSectionTest` | the section on screen, above videos, and tapping the right song |
| live | `lib/innertube/…/LiveMusicSearchTest` | real YouTube Music — `RUN_LIVE_MUSIC=1`, run and passing on 2026-08-11 |
| unit | `lib/innertube/…/MusicPageParserTest` | album, artist, radio and library pages against real trimmed fixtures (2026-10-09) |
| unit | `lib/innertube/…/HttpYouTubeMusicTest` | the requests: filters, the two-call album link, the radio continuation, TV-client library with the token |
| live | `lib/innertube/…/LiveMusicCatalogueTest` | albums, an album link, an artist, a radio's second batch — 4/4 on 2026-10-09 |
| unit | `app/…/MusicRadioTest`, `MusicPlaysAsSoundTest`, `OneSongOneIdTest` | top-up, stop, no repeats offered; music routed and armed as sound; one id per song |
| unit | `app/…/SearchViewModelTest` | the Music scope asks only music; global search never asks for albums or artists |
| unit | `app/…/AutoAdvancerTest`, `ShuffleUpNextTest`, `core/domain/…/OfflineCountTest` | repeat one / queue, shuffle, offline counts |

`songs-search.json` is a real response trimmed to four rows with every field the parser reads left
exactly as YouTube sent it. A hand-written fixture would only prove the parser agrees with my idea
of the shape, which is the very thing that is wrong when this breaks.
