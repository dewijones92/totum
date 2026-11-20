---
title: A channel's past live streams are its Live tab, found through the channel's own tab list, and shuffle all walks them
kind: adr
status: accepted
updated: 2026-11-20
---

# 178. A channel's past live streams are its Live tab, found through the channel's own tab list, and shuffle all walks them

- Status: Accepted
- Date: 2026-11-20

## Context

Dewi, 2026-11-20: past live streams were missing from channel pages. A channel's Videos tab does not list them;
YouTube keeps them under the channel's **Live** tab (`/streams`). Checked anonymously against The Tennis Podcast: the
streams tab holds 30 items, the first being `atn53OrYlZU`, which the Videos tab lacks. The channel's RSS feed already
carries lives, so groups and New uploads showed them while the channel page and Shuffle all did not
([ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)).

Two things measured on 2026-11-20 (anonymous WEB `browse`, nothing sent but a public channel id) shaped the design:

- **A channel with no Live tab does not answer "no".** Asked for the streams params, kurzgesagt and Veritasium came back
  with their **Home** tab selected (1.9 MB and 2.2 MB, 98 and 119 tiles) and The Beyond Tennis Podcast with its
  **Videos** tab selected (20 tiles). Parsed like a Live tab, those would list a channel's featured and recent uploads
  under "Live", and cost megabytes to do it.
- **Every channel page already says whether it has a Live tab.** The Videos, Shorts and Playlists responses carry the
  whole tab list; the Live entry's endpoint is `/@handle/streams` (or `/channel/UC…/streams`), selected or not.

## Decision

- **A separate Live tab**, like YouTube's (Dewi's choice over merging lives into Videos). `YouTubeChannel.streams`
  browses the stable params `EgdzdHJlYW1z8gYECgJ6AA==` and parses with `LockupParser.videos`, the parser the Videos tab
  uses. `ChannelViewModel.Tab.LIVE` is listed only while the channel has streams, so a channel with none shows exactly
  the tabs it showed before; a Live tab that turns out empty on a refresh takes itself away and selects Videos.
- **A first page is trusted only when the selected tab is the Live tab** (its endpoint ends `/streams`). Anything else is
  an empty page with no continuation, logged as "has no Live tab".
- **The tab list on any first page is remembered**, per channel, in `HttpYouTubeChannel` (in memory: the next first page
  of any tab re-reads it, so a channel that grows a Live tab is noticed the next time its Videos page loads). A channel
  known to list no Live tab is never asked for streams: no request, an empty page.
- **The Live tab is asked after the Videos page** on the channel page (`loadVideos(...)?.invokeOnCompletion`), and in
  `WholeSource` each source's `LIVE` walk starts only when its `UPLOADS` walk has finished, so the tab list is known
  first. For a channel without a Live tab that is **zero extra requests**.
- **Shuffle all always walks lives** (`SourcePart.LIVE`), whether or not Include Shorts was ticked, for a channel, a
  group and a ticked selection alike; a podcast has none (`AppSourcePages` answers an empty page). It goes through the
  same InnerTube client as every other tab, so it is inside the polite gate (at most 4 in flight) and `WholeSource`'s own
  four-request semaphore.
- **Groups and New uploads are unchanged on purpose.** They read a channel's page plus its RSS feed, and the feed
  already carries the newest 15 lives; a group's older lives appear through its Shuffle all, which walks them. Adding a
  streams request to every group read would have cost a request per member per open for the sake of lives older than the
  feed.
- A handle-only channel (no `UC…` id) gets no Live tab, exactly as it gets no Shorts today
  ([consistency leftovers](../todos/consistency-leftovers.md)).

## Consequences

- Request budget: a first-page Live request is made only for a channel whose tab list says it has a Live tab, or whose
  tab list has not been read yet in this process. Dewi's real state is 1,622 followed channels in groups of 4 and 5; a
  group's Shuffle all adds at most 4 or 5 first pages plus 30 tiles a page for channels that stream, and a select-all
  shuffle adds none for the channels that do not. `WholeSource`'s summary line now says pages by part and how many
  sources had streams.
- Logs (`channel`): `<id> lists a Live tab (N channel(s) known)`, `<id> has no Live tab: YouTube answered with another
  tab`, `tab lists read for N channel(s): L with a Live tab, S Live first page(s) skipped so far` (every 50), `"<channel>"
  Live tab shown, N stream(s) (more=…)` or `hidden, no streams`; `shuffle`: `gathered … (uploads=a, live=b, shorts=c) …
  N source(s) with live streams (M found)`.
- A past live stream that is also in the RSS feed or the Videos tab is one row (items are de-duplicated by id).
- Tests: `HttpYouTubeChannelStreamsTest` (a captured Live response, a captured response for a channel with none, a Home
  response, the continuation, the remembered tab list in both directions), `WholeSourceTest` (lives walked unasked, after
  the uploads, an empty live walk is not "unfinished"), `AppSourcePagesTest` (continuation, a handle resolved once, no
  podcast request), `ChannelTabsReloadTest` (the tab appears only with streams, asked after Videos, paging, refresh).
