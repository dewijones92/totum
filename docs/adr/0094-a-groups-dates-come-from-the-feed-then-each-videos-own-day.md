---
title: A group's dates come from the channel feed, then each video's own publish day
kind: adr
status: accepted
updated: 2026-10-29
---

# 94. A group's dates come from the channel feed, then each video's own publish day

- Status: Accepted
- Date: 2026-10-29

## Context

Dewi, 2026-10-29: one list of Backpacker Ben, Carry On Cat and Bald and Bankrupt, newest first, *"make sure the date
ordering works correctly … scroll down to … 40 items … cross-check"*. The July groups (`GroupFeed`) merged "newest
first" by `publishedAt`, but a channel page's items carry only an age ("3 weeks ago"), so on the device all 60 items
were undated and the "merge" was Backpacker Ben's 20, then Carry On Cat's 20, then Bald and Bankrupt's 20 (Carry On
Cat's newest, truly first, sat 21st). Ages are also too coarse: "4 months ago" put a 14 May video above a 2 June one.

## Decision

- A channel member (`ChannelSourceItems`) reads its page as before, plus its **RSS feed** (`LatestUploads.feedOf`,
  exact times for its newest 15, Shorts and live included). Page items the feed does not cover ask YouTube for their
  **publish day** once (`InnerTubeClient.next` + `WatchNextParser.publishedOn`), kept for good in
  `files/publish_days.json` (`PublishDays`): a publish day never changes. Taken as noon UTC.
- `datedChannelItems` never changes an exact date; anything still undated gets its age, held no newer than the item
  above it on the channel's own page and no older than the next exact date below, so a channel's own order survives.
  Feed items missing from the page (a Short, a live) are kept with their exact time.
- All requests go through the polite gate (the feed is ~6 KB; a lookup is one small `next` call per video, once).

## Consequences

- Checked on totum-api35 against ground truth (the three RSS feeds, and the nine older videos' watch pages): 60 items,
  54 with a known true date, 0 wrong days, 0 order inversions. The first open of a group costs up to (page − 15)
  lookups per channel; later opens none.
- Videos published the same day in two channels may swap (day precision below the feed's 15).
- Tests: `GroupDatesTest`, `GroupFeedTest` (ages alone still merge; older videos take their real day, both seen
  failing first).
