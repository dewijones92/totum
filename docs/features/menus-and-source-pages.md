---
title: Menus go where they say, and every source has its picture
kind: feature
status: shipped
area: ui
updated: 2026-09-24
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

## The seams

- **`MediaSource.artworkUrl`** — one field for both pillars. Podcast: channel `itunes:image`, else
  `<image><url>`. Channel: the account subscription's avatar (`SubscribedChannel.avatarUrl`), or the
  subscription matched by `UC…` id on a channel page opened from a row. Persisted in
  `podcast_feeds.artworkUrl` (migration 22→23; that table holds both pillars). A refresh fills it for
  existing subscriptions.
- **Episode thumbnail** = its own image, else the show's.
- **`SourceArtwork` / `SourceChip`** — one composable each, round for a channel, rounded square for a
  show, used by both tabs' chip strips and by `SourceHeader`.
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
