---
title: Menus go where they say, and every source has its picture
kind: feature
status: shipped
area: ui
updated: 2026-11-06
---

# Menus go where they say, and every source has its picture

Dewi, 2026-09-24: *"make sure podcasts have their menus make sense (i.e. go to podcast goes to
podcast feed etc) … check other menus also … also make the pictures show for podcasts"*.

## What was wrong (all found by reading the code, then confirmed on `totum-api35`)

| Where | Symptom | Cause |
|---|---|---|
| Queue, History, Library, local playlists, the player | "Go to podcast" did nothing | The shell-hosted `ItemActions.goToSource` kept only `VideoChannel` results; a `PodcastFeed` was dropped silently |
| An episode of a feed you no longer follow | "Go to podcast" ran a yt-dlp extraction of the mp3 and found nothing | `DefaultSourceLocator` treated anything unsubscribed as a video |
| The player's ⋮ sheet on a podcast | Said "Go to channel" and offered "Play audio only" | `ItemActionSheet` defaulted its pillar to VIDEO and the player never passed one |
| A podcast's page, a channel's page | Each row offered "Go to podcast/channel" — to the page you were on | The app-wide default applied there too |
| A podcast's page opened from the Podcasts tab | Back quit the app | The tab had no `BackHandler`; the Videos tab did |
| The Queue's ⋮ sheet | Offered "Add to queue" for something already queued | Same app-wide default; it only duplicated "Move to bottom" |
| An unfollowed feed's page | Would have shown "Nothing here yet." | The page only read stored episodes |
| Subscription chips, source page headers | No picture at all — for either pillar | `MediaSource` had no artwork; the feed's own `itunes:image` / `<image>` was never parsed and YouTube's channel avatar was parsed then thrown away |
| Episodes whose feed only has show-level art | Placeholder glyph | Episode thumbnail was the episode's own `itunes:image` only |

### Found by the independent review (same day), fixed

| Where | Symptom | Fix |
|---|---|---|
| The player's ⋮ for an audio-only copy of a video played from Library | Said "Go to podcast" and hid "Download video" | `PlayableItem.pillar` — a video if the ITEM is one, whatever handle it plays through; used by the player sheet and the Queue / History / playlist rows |
| Subscribing from a podcast page | Left `subscribing = Done` in the shared view model, so the Podcasts tab's "+" dialog then closed instantly; a failure showed nowhere | The page shows progress, toasts a failure, and resets the state itself |
| Opening a feed page you do not follow | Waited for up to 30 remote chapter files | `preview` skips remote chapters (inline ones still come through) |
| Rows and playback of episodes queued / downloaded before the show had artwork | Placeholder for ever — those tables are written once | `withArtworkFrom` at render time (`LocalSourceArtwork`) and at the one play seam (`PlaybackQueue.route`), so the notification and lock screen get it too |
| A torrent file | Offered "Go to podcast", which did nothing | `SourceLocator.canLocate` gates the action |
| A followed feed's page opened before the Podcasts tab ever composed | Flashed a Subscribe button and started a preview | The page waits for the stored subscriptions to be known |
| Peek from a shell page / Go to from the player or the Shorts reel | The player opened underneath the page / the page opened underneath the player | Shell pages draw below the player and reel; opening one closes both |

Second review, same day: Back on a shell page lost to a tab's own page hidden underneath (shell
handlers are now re-registered, keyed on the page, so the page on screen wins — `BackFromPodcastPageTest`
fails without it); a playlist opened from one channel stayed over the next; playback still told the
player a Library-played audio copy of a video was a podcast, so YouTube never heard of it and history
recorded it with a podcast handle (`PlaybackQueue` now passes `PlayableItem.pillar`, and the history
hook records a video with its watch URL); the artwork lookup suspended before playback was claimed; and
a subscribe from a page now reports to the page alone rather than through the Podcasts tab's dialog state.

Left as they are, on purpose: the Videos, Search and Podcasts tabs keep their own in-tab "go to", so a page opened from a tab stays in that tab; a backup does not carry artwork (the next refresh restores it).

## The seams

- **`MediaSource.artworkUrl`** — one field for both pillars. Podcast: channel `itunes:image`, else
  `<image><url>`. Channel: the account subscription's avatar (`SubscribedChannel.avatarUrl`), or the
  subscription matched by `UC…` id on a channel page opened from a row. Persisted in
  `podcast_feeds.artworkUrl` (migration 22→23; that table holds both pillars). A refresh fills it for
  existing subscriptions.
- **Episode thumbnail** = its own image, else the show's.
- **`SourceArtwork` / `SourceAvatar`** — one composable each, round for a channel, rounded square for a
  show, used by both tabs' avatar strips and by `SourceHeader`.
- **The shell hosts any source**: `ProvidePlayStates(onOpenSource: (MediaSource) -> Unit)` →
  `ShellOverlays` shows `ChannelScreen` or `PodcastFeedScreen` from an exhaustive `when`.
  `ItemActions.openSource` lets a non-row (a podcast search result) open a page the same way.
- **`PodcastRepository.preview(feedUrl)`** — fetch + parse + map with no storage, sharing one loader
  with `subscribe` and `refresh`. The feed page previews when you do not follow the show, so "Go to
  podcast" from history and a tapped podcast search result both show real episodes.
- **`ItemActionSheet(pillar = …)`** — defaults to `item.pillar`; the player passes the queue entry's
  `PlayHandle.pillar`. Video-only actions (switch mode, download video) are gated on it.

## Diagnostics

`[nav] go to source "<title>" [pillar=… sourceId=…] -> podcast|channel "<name>" <url>` or
`-> nothing found, not navigating`; `[nav] open source …`; `[nav] back from podcast …`;
`[podcast] preview "<title>" episodes=N artwork=true|false <url>`.

## Tests

`GoToSourceGoesThereTest`, `BackFromPodcastPageTest`, `QueueRemoveTest.theQueueMenuDoesNotOfferToAdd…`
(instrumented); `RssParserTest`, `DefaultPodcastRepositoryTest`, `SourceLocatorTest` (JVM). Every one
was run against the code with its fix undone and failed at the named assertion.

## Go to channel while YouTube bot-checks (2026-10-15)

A video whose listing names no channel (a search result) used to be located by a yt-dlp extraction, which a bot
check refuses, so "go to channel" did nothing (report 0.1.626, "peep show channel not loading"). Signed in, the
account's watch page names the channel first (`AccountChannel`, `AccountChannelLookup` in `SourceLocator`); yt-dlp
is the fallback. Test: `SourceLocatorTest`.

## Links and copy (2026-10-15)

Web addresses in descriptions and show notes are links (`LinkSpan.kt`, beside the timestamp seeks); the sign-in
page's google.com/device is a link and its code has a copy button (long press copies too); Details' link and id
have copy buttons; the version line copies on long press. Test: `LinkSpansTest`.

## Removing from a playlist can be undone (2026-11-06)

Deleting a whole playlist (from the Playlists list, in bulk, or from its own page) and clearing the play history offer
Undo through the same helper, with no confirm or hold in front of them (the coordinator's call; Dewi delegated it,
2026-11-06). The stores return what they removed and take it back (`LocalPlaylistStore.delete`/`restore` with a
`RemovedPlaylist`, `PlayHistoryStore.clear`/`restore`); a playlist returns under its own id with its items in order and
its old place in the list, the history returns in its old order behind anything played since
([ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md), third pass). Tests:
`PlaylistRemovalsTest`, `HistoryClearingTest`, the store tests, `MultiSelectTest` and `RoomUndoTest` (the two instrumented
ones are written, not yet run on a device).

Removing items from a local playlist (one row, or several selected) offers Undo on the shell's snackbar. Undo puts them
back **in their old places** (`LocalPlaylistStore.insertItem(id, item, at)`, the same idea as the queue's `restoreAt`). In Starred, Undo stars them again, which also re-likes a
YouTube item on the account.

## 🙅 Not for me (2026-11-06)

Every item's menu has **🙅 Not for me**: the item leaves the queue (Undo puts it back and allows it again) and is
written to the not-for-me list (`NotForMe` in `:core:data`, `not-for-me.json`, part of the Drive backup). The rule is
"never *suggested* again", decided per surface:

- **Filtered** (the app chooses these for you): Explore's related list, the Related tab on the watch page, the Videos
  subscriptions feed, group pages, new uploads and group news (both via `LatestUploads`), radio and mix start and
  top-ups, shuffle all and artist shuffle, and Ask (the AI is told the titles to avoid and a matching suggestion is
  dropped).
- **Deliberately not filtered** (you asked for or opened it): channel and podcast pages and their episode lists (the
  source's own catalogue), search results, playlists, Starred, history, downloads and Library, and the queue itself.
  Adding a listed item by hand from one of these still works. **The All subscriptions page is not filtered either**
  (2026-11-06, the coordinator's call; Dewi delegated it): its latest-upload line is a fact about your own subscription,
  not a suggestion, so a 🙅 item is still named there (and carries the live tag like every row).
- **On what is playing now,** 🙅 Not for me skips to the next item (with nothing after it, playback pauses), the
  coordinator's call; Dewi delegated it, 2026-11-06. Details under "Removing the playing entry" in
  [playback-queue.md](playback-queue.md).

Settings > Not for me lists the items and allows one again (with Undo). It is per item, not per channel
([ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md)).

## Tests owed (2026-11-06 consistency sweep)

A book row's long-press menu (Share, Not for me, Details; no Star or queue entries); a torrent pack's menu having
⭐ Star every file and 🙅 Not for me (and `StarListAction` toggling on only when every file is starred);
`SearchHit.Book.asItem()`.
