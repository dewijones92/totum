---
title: Consistency leftovers (ADR 108)
kind: todo
status: swept 2026-11-13, 3 fixed, 8 open
area: ui
priority: medium
updated: 2026-11-13
---

# Consistency leftovers

What [ADR 108](../adr/0108-consistent-everywhere-and-its-recorded-exceptions.md) still owed after the 2026-10-31
audit (`C:\Users\DewiJones\claude-html\totum-consistency-audit-2026-10-31.html`: 49 inconsistencies, D1 to D8),
re-checked against the code on 2026-11-13 (`main` at `beaaafc8`), not against the ADR's word. The recorded
exceptions in the ADR are decisions, not leftovers, and are not listed.

Effort: S under an hour, M a few hours, L a day or a schema change.

| Item | State | Evidence | Effort |
|---|---|---|---|
| Channel page rows carry the account's watched marks (triage finding 95) | ✅ done 2026-11-13 | `ChannelViewModel.readAccountProgress` asks `ChannelAccountProgress` when the Videos tab loads, the same read a group makes (`ChannelSourceItems.accountProgressOf`); `ChannelAccountProgressTest` (4 tests); [ADR 96](../adr/0096-a-group-asks-the-account-how-far-each-video-was-watched.md) amended | done |
| Clear all recent searches offers Undo | ✅ done 2026-11-13 | `SearchViewModel.clearHistory` returns the slots, `restoreHistory(List)` puts them back, `rememberClearRecent` in `ui/common/RecentSearches.kt`; three tests in `SearchViewModelTest` | done |
| The stale comment saying stored rows drop duration, views and date (audit 49) | ✅ done 2026-11-13 | `CachedFeedEntities.kt:19-22` now says what `PlaylistItemColumns` really carries; `PlaylistItemColumns.kt:50-80` has the columns | done |
| D3: fold the thin top busy bar into the progress strip | 🔓 open | `busy/BusyBar.kt:51` still draws its text-less bar, mounted at `ui/TopLayers.kt:15`; needs a design for background lookups, which the bar covers today and which must not fill the strip | M |
| Audit 11: stored queue, history and download rows lose show notes and chapters | 🔓 open, left alone (touches downloads) | `PlaylistItemColumns.kt` `playlistItemFrom` (about line 90) builds a `MediaItem` with no `description` (`MediaItem.kt:48`) or `chapters` (`MediaItem.kt:69`); `TotumDatabase.kt:27` is `version = 28`, so it is a v29 bump with `RollBack.kt` and the whole `:core:database` instrumented suite, and `RoomDownloadStore` implements the same columns | L |
| Audit 32: private page spinners instead of the progress strip | 🔓 open | `AllSubscriptionsScreen.kt:145,156`, `AddChannelDialog.kt:59`, `AddPodcastDialog.kt:62`, `PodcastFeedScreen.kt:154`, `SearchScreen.kt:209,452,482`, `ChannelScreen.kt:416`, `MusicPageParts.kt:38`, `AccountScreen.kt:84`, `StatsScreen.kt:67`, `AskScreen.kt:358`, `SearchTabSections.kt:81`, `SearchSections.kt:130` | L |
| Audit 34: empty states come in six looks | 🔓 open | `EmptyState` (8 screens), `ListMessage` (`ui/common/ListStates.kt:17`, 7 screens), `sectionEmpty` (`DownloadsSections.kt:108-119`), `Hint` (`MusicScreen.kt:96`, `DiagnosticsScreen.kt:66`), `PlayerNote` (`RelatedVideos.kt:40`, `PlayerComments.kt:51`), `LyricsNote` (`PlayerLyrics.kt:67`), bare `Text` (`JellyfinScreen.kt:137`, `GroupScreen.kt:137`, `PlayerTabs.kt:118`, `GroupPicker.kt:68`). Compose-only, no JVM seam, so a fix needs screenshots and an instrumented check | M |
| Audit 43: three search-field designs, and the group "add a channel" search has no recent searches | 🔓 open | `SearchScreen.kt:230,304` (pill), `ListFilter.kt:163-196` (`FilterField`), `GroupEditSheet.kt:131,154`, `ChannelScreen.kt:247`, `AskScreen.kt:184,234`, `AddToPlaylist.kt:121`; recents are used by Search, filters, channel, transcript and Ask only | M |
| Group membership Undo re-adds the channel at the end, not in its old slot | 🔓 open | `GroupEditSheet.kt:71-80` restores through `toggleMember`; `SourceGroupStore.kt:31` only appends. Needs an insert-at API on the store and the Room store | M |
| Follow-up of finding 95: Shorts tab, later pages and handle-only channels still lack the account's marks | 🔓 open | `ChannelAccountProgress.read` browses one TV page of the channel's Videos tiles and logs `no channel id` for a `/@handle` URL; `ChannelViewModel.loadShorts` and `pageMore` ask nothing | S |
| Search results and related videos show only the marks the history and feeds happened to carry | 🔓 open, needs a decision | their rows come from the web InnerTube, which carries no resume overlay (`HttpYouTubeFeeds.kt:43` and `HttpYouTubePlaylists.kt:45` are the only parsers that feed `SeenProgress`); there is no per-video progress call to ask, so this is a product call, not a bug fix | M |

## What was checked and is still fixed

Re-read in the code on 2026-11-13, so the ADR's "fixed" holds: search results carry SHORT and LIVE
(`InnerTubeVideoSearchSource.kt:34-36`), a video opened by link keeps its live kind (`ContentKinds.kt:7`, used by the
resolver), the channel fallback keeps views and members-only (`DefaultChannelRepository.kt:42-43`), one failed publish-day
lookup is asked again (`PublishDays.kt:45`), podcast-search rows have the follow mark (`SearchScreen.kt:412`), Playlist,
Podcast feed and Playlists use the skeleton (`ListLoading` in all three), the clock parser is one copy
(`lib/common/.../ClockText.kt`), an episode with no guid is keyed by title and date (`DefaultPodcastRepository.kt:224-226`),
and the wording vocabulary (error, retry, subscribe, members-only, live) reads one way in `strings.xml`.
Audit items 1 to 10, 12 to 31, 33, 35 to 42, 44 to 48 were fixed in batches 1 to 4 (ADR 108); the ones above are the
ones I opened the code for.

## Not touched on purpose

The device picker, targets, casting and downloads are other builders' ground (2026-11-13): audit 11 above reaches the
downloads store, and the Pi target's remote queue is recorded in ADR 108 as not covered.
