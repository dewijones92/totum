---
title: The player says everything about the item
kind: adr
status: accepted
updated: 2026-10-16
---

# 65. The player says everything about the item

- Status: Accepted
- Date: 2026-10-16

## Context

Dewi: "I want the … player page in an elegant way to display everything about the item being played", and, a general
rule, "not just limited to videos, but to any item". He had seen facts on a channel's list (date, views) that the player
did not show, and found the Details tab empty: signed in, the TV player response carries no description.

## Decision

- An "ℹ️ About this" card tops the Details tab: one label and value per fact, built by the pure `itemFacts` (`ItemFacts.kt`)
  from the playing `MediaItem` (`LocalPlayingItem`), the playback state and the disk. Facts: published (long date and
  how long ago), length, views, by, publisher, album, kind, members-only, chapters, subtitles, skip segments, the copy
  on disk and its size, and the source. A fact the item does not know is left out, never shown as "unknown".
- The description comes from the watch page when the player response has none. Two routes, one parser
  (`WatchNextParser.description`, which reads the TV page's `descriptionBodyText` and the WEB page's
  `attributedDescription`, preferring the playing video's `videoSecondaryInfoRenderer`): the anonymous WEB `/next`
  that the Related tab already fetches carries it for a YouTube video (none when that request fails, and only for the
  video it was fetched for, never a podcast playing after it)
  (`RelatedResult.description`, shown by the Details tab), and the account's `/next` is asked only for a row with no
  title. The first version used only the second route, which a titled row (every feed and channel row) never takes, so
  Details stayed empty for most plays; the batch review caught it.
- A channel page stamps its own channel on the items it lists (`MediaItem.listedOn`), because YouTube's channel grid
  omits the channel on every row; so a video played from a channel page knows whose it is, and its follow mark is
  right (it said ❔ unknown on the page of a channel Dewi follows).
- The report carries a `playing.facts` line.

## Consequences

- Tests: `ItemFactsTest`, `TheWatchPageDescribesATvPlayTest`, `AnAutoCaptionIsNamedOnceTest` (an auto caption is no
  longer labelled "(auto-generated) (auto-generated)"), `AVideoOnAChannelPageIsThatChannelsTest`.
- A channel page logs how many of its items arrived without their channel.
