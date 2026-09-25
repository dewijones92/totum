---
title: Menus go where they say, and every source has its picture
kind: feature
status: shipped
area: ui
updated: 2026-09-25
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
