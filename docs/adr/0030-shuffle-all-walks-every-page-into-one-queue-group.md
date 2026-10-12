---
title: Shuffle all walks every page of a source into one queue group
kind: adr
status: accepted
updated: 2026-10-12
---

# 30. Shuffle all walks every page of a source into one queue group

- Status: Accepted
- Date: 2026-10-12

## Context

Dewi, 2026-10-12: *"I want the ability to shuffle ALL videos of a channel"*. Asked, he chose: ask about
Shorts each time; do not skip watched items; no cap however large the channel; the result as its own
thing in the queue; and the same on groups. A channel page only ever loaded one InnerTube page at a
time as you scrolled, and nothing could fetch the lot.

## Decision

- One seam for both pillars: `WholeSource` (`:core:data`) walks a list of sources to the end through a
  `SourcePages` port, concurrently per source, three attempts per page, stopping on a repeated
  continuation or a runaway 2,000 pages, and says which parts it could not finish.
- `AppSourcePages` is the only place that knows pillars: a channel known by id pages InnerTube's
  uploads (and Shorts when asked); a handle-only channel comes from yt-dlp in one page; a podcast
  gives its stored episodes, or a preview of its feed.
- `ShuffleAll` shuffles everything gathered and inserts it after what is playing as one `QueueGroup`
  (`shuffle:<source>`), like album shuffle, so it can be collapsed, skipped or removed in one go.
  Members-only videos are left out (they cannot play) and counted in the log.
- 🔀 in the header of the channel page and the podcast page; a **Shuffle all** row under the feed
  chips when a group is selected. "Include Shorts?" is asked whenever a channel is involved; a
  podcast-only shuffle starts straight away. The load runs in the app scope, so leaving the page does
  not cancel it, and the button shows the running count.

## Consequences

- Playback starts once every page has loaded (a large channel can take tens of seconds; the count
  shows progress), not at the first page. A uniform shuffle needs the whole list.
- A huge channel goes into the queue in full, as Dewi asked; the queue's own group collapse is what
  keeps it manageable.
- Logged under `[shuffle]`: sources, pages, items, unfinished parts, members-only left out, time taken.
