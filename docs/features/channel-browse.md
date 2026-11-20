---
title: Explore channel content (InnerTube tabs)
kind: feature
status: shipped
area: channel
updated: 2026-11-20
---

# Explore channel content

Rebuild the channel page with tabs — **Videos / Shorts / Playlists** — fetched
via InnerTube. Videos gain "x days ago" upload dates automatically (fixing part
of [upload-dates.md](upload-dates.md)). Chosen over the yt-dlp path because
InnerTube returns dates for free and a richer tabbed structure.

Today the channel page (`ChannelScreen`/`ChannelViewModel`) shows a single flat
`fetchChannelVideos` (yt-dlp) list with **no dates** and no Shorts/Playlists.

## InnerTube shape (reverse-engineered live, 2026-07-24)

Public channel content — use the **WEB client, unauthenticated** (works signed
out; channel content is public). Endpoint: `POST youtubei/v1/browse` with
`{"context":{"client":{"clientName":"WEB","clientVersion":"…"}},"browseId":"<UCID>","params":"<tab>"}`.

`browseId` is the channel's `UC…` id. The tab `params` are **stable across all
channels**:

| Tab | `params` | Item renderer |
|---|---|---|
| Videos | `EgZ2aWRlb3PyBgQKAjoA` | `lockupViewModel` (`LOCKUP_CONTENT_TYPE_VIDEO`) |
| Shorts | `EgZzaG9ydHPyBgUKA5oBAA==` | `shortsLockupViewModel` |
| Playlists | `EglwbGF5bGlzdHPyBgQKAkIA` | `lockupViewModel` (`LOCKUP_CONTENT_TYPE_PLAYLIST`) |
| Live (2026-11-20) | `EgdzdHJlYW1z8gYECgJ6AA==` | `lockupViewModel` (`LOCKUP_CONTENT_TYPE_VIDEO`, "Streamed 3 days ago") |

Path to items: `contents.twoColumnBrowseResultsRenderer.tabs[selected].tabRenderer.content`
→ Videos/Shorts: `richGridRenderer.contents[].richItemRenderer.content.<renderer>`;
Playlists: `sectionListRenderer.contents[].itemSectionRenderer.contents[].gridRenderer.items[].lockupViewModel`.

### Videos — reuse `RelatedVideosParser`

Same `lockupViewModel` shape that `RelatedVideosParser` already parses:
`contentId` = videoId, `metadata.lockupMetadataViewModel.title.content`,
`metadataRows` → `["3.3M views", "2 days ago"]` (last part = **publishedText**),
duration from the thumbnail badge overlay. → `FeedVideo` (has `publishedText`).
**Extract the shared lockup→`FeedVideo` collector so both use one copy (DRY).**

### Shorts — new parser for `shortsLockupViewModel`

- videoId: `onTap.innertubeCommand.reelWatchEndpoint.videoId`
- title: `overlayMetadata.primaryText.content`
- views: `overlayMetadata.secondaryText.content` (no date on shorts)
- → `FeedVideo` with `Kind.SHORT`.

### Playlists — new parser + reuse `Playlist` model

`lockupViewModel` `LOCKUP_CONTENT_TYPE_PLAYLIST`: `contentId` = playlistId,
title from `lockupMetadataViewModel`. Reuse `:lib:innertube` `playlists/Playlist.kt`
+ the existing `PlaylistScreen`.

## Plan

1. `:lib:innertube` `YouTubeChannel` port + `HttpYouTubeChannel` (browse each tab).
   Add `InnerTubeClient.browseWeb(browseId, params)` (WEB, no auth).
2. Parsers: shared lockup collector (Videos), `ChannelShortsParser`, `ChannelPlaylistsParser`. Fakes.
3. Resolve the channel to a `UC…` id (subscribed channels carry it in `channelUrl`;
   pasted `@handle` URLs need resolving — keep yt-dlp `fetchChannel` for that, or
   an InnerTube `navigation/resolve_url`). **Decide + note here.**
4. `ChannelViewModel` loads the three tabs (map `FeedVideo` → `MediaItem`);
   `ChannelScreen` gets a `TabRow`.
5. Live/on-device verify each tab; tests for each parser against a captured fixture.

## Risks

- InnerTube shapes drift; parsers must be null-tolerant (kotlinx JSON present-null
  gotcha — use `as? JsonArray`, never `?.jsonArray`).
- `@handle` → `UC…` resolution path.
- Shorts thumbnails came back empty in one probe — confirm the thumbnail source.

## Shipped 2026-07-24

All three tabs live (InnerTube WEB browse). Videos carry upload dates; Shorts are
tagged SHORT; Playlists are tappable (open the existing playlist screen). Verified
on-device (3Blue1Brown). Minor follow-ups: Shorts + Playlist tile thumbnails come
back as placeholders (those lockup shapes hold the image under a different key
than videos) and a playlist's subtitle is "View full playlist" rather than a
count — cosmetic.

## Reopening a channel reloads its tabs (2026-11-06)

The channel's view-model outlives the screen, so a reopened channel used to show the list it loaded the first time
(report 20261101T222816: a 6-hour-old upload missing). Entering the screen now reloads the Videos, Shorts and
Playlists lists (those already loaded) when 5 minutes old or when their last load failed, and so does selecting a
loaded tab: the old list stays with a progress bar, a failed refresh keeps it, and the diagnostics say whether the
list was reused and how old it was. One rule and one load path for the three tabs.

## Watched marks on the channel page (2026-11-13)

The rows here showed no tick, bar or started dot for videos the account had watched elsewhere, while the same
channel's rows did inside a group (triage finding 95, [ADR 96](../adr/0096-a-group-asks-the-account-how-far-each-video-was-watched.md)).
Loading the Videos tab, on opening and on every stale refresh, now also asks the account for that channel through
`ChannelAccountProgress` (`video/`), the one read a group uses; the list is not held back and the rows pick the marks up
live. Only the Videos tab's first TV page is asked; Shorts, later pages and a handle-only channel are listed in
[consistency-leftovers](../todos/consistency-leftovers.md). Log: `[account-progress] "<channel>": account tiles=N progressKnown=M …`,
and `[channel] "<channel>" account progress could not be read` when it fails. Test: `ChannelAccountProgressTest`.

## Playlists open from every channel (2026-09-06)

`ChannelScreen` takes `onOpenPlaylist`; the Videos tab wired it and the two other hosts — the shell's
"go to channel" overlay and a channel browsed from Search — passed `{}`, so a channel's playlists
listed fine there and none of them would open. Both now hold a playlist overlay of their own, in the
same shape as the channel overlay they sit on.

## A Live tab for past streams (2026-11-20)

A channel's Videos tab does not list its live streams, so a channel that streams (The Tennis Podcast: 30 on its Live tab,
the newest, `atn53OrYlZU`, not on Videos) looked as if it had never gone live. The page now has a **Live** tab, shown
only while the channel has streams ([ADR 178](../adr/0178-a-channels-past-streams-are-its-live-tab-found-through-its-tab-list.md)):

- `YouTubeChannel.streams` / `HttpYouTubeChannel.streams`, the Videos parser, a Page with its own continuation.
- It is asked for **after** the Videos page loads, once on opening and again when the tab is 5 minutes old (the same
  rule as the other three tabs); a failed read leaves the tab hidden and says why in the log.
- A channel without a Live tab answers the streams request with its Home or Videos tab selected (1.9 to 2.2 MB for a big
  channel), so a first page counts only when the selected tab's endpoint ends `/streams`. The tab list in any first page
  says whether a Live tab exists, and a channel known to have none is never asked.
- The filter and the list shape (`ShapeControl`) work on it like on Videos and Shorts. Shuffle all walks it
  ([shuffle-all.md](shuffle-all.md)); a handle-only channel has no Live tab, as it has no Shorts.

Log (`channel`): `"<channel>" Live tab shown, N stream(s) (more=…)` / `hidden, no streams`, `<id> lists a Live tab`,
`<id> has no Live tab: YouTube answered with another tab`, and every 50 channels a count of tab lists read and Live first
pages skipped. Tests: `HttpYouTubeChannelStreamsTest`, `ChannelTabsReloadTest` (six new).
