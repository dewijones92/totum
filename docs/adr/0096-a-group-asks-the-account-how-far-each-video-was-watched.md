---
title: A group asks the account how far each of its videos was watched
kind: adr
status: accepted
updated: 2026-10-29
---

# 96. A group asks the account how far each of its videos was watched

- Status: Accepted
- Date: 2026-10-29

## Context

Dewi, 2026-10-29, on the Travel Group page: *"I could have sworn I watched some of these in my Chrome browser. So it
should have some sort of indicator here that I'm halfway through or watched."* A row's account progress comes from
`SeenProgress` (every signed-in TV list the app reads) and the history's first three pages. A group reads each channel
anonymously (its page, its RSS, each video's publish day), none of which carries progress, so only videos still in the
last 37 of his history could show a bar: of that group, only Tunisia!, at YouTube's 10% floor.

The signed-in TV browse of a channel id carries a resume overlay on every tile the account has watched: on 2026-10-29
Backpacker Ben gave 28 tiles (15 of them watched), Carry On Cat 24 (12) and Bald and Bankrupt 52 (32).

## Decision

- **While a group is read, each video member's channel is also browsed as the account** (`YouTubeFeeds.channel`,
  the same TV browse and `VideoTileParser` as every account feed), in parallel with its page. The parse feeds
  `SeenProgress`, so the rows' ✓, bar and "started" dot come from the one path every list already uses; nothing new
  draws them.
- **A failure there costs nothing but the marks**: signed out, an error, or a channel URL with no `UC…` id is logged
  and the group is listed as before (`ChannelSourceItems.accountProgressOf`).
- The 10% floor rule ([progress-sync](../features/progress-sync.md)) is unchanged: a video YouTube reports at 10%
  shows the "started" dot, not a bar.

## Consequences

- One more signed-in TV request per channel each time a group is opened or refreshed, through the polite gate.
- Each one logs `[group] "<channel>": account tiles=N progressKnown=M <id>=<pos>s/<dur>s …`, so a report shows which
  videos had marks and what YouTube said.
- Only the tiles of the channel's first TV page are asked (about 25–50), which covers a group's recent videos; older
  ones still rely on the history.
- Verified on totum-api35, signed in to Dewi's account: the Travel Group page showed ✓ on a finished video, a bar on
  a part-watched one and the dot on three at 10%.
- Tests: `HttpYouTubeFeedsTest` (a channel browsed as the account, its progress seen) and `GroupFeedTest` (every
  member asked, a failure loses no items).
