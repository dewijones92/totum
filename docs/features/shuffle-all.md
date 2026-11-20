---
title: Shuffle all (a channel, a podcast or a group)
kind: feature
status: shipped
area: queue/channel
updated: 2026-11-20
---

# Shuffle all

🔀 on a channel page or a podcast page, 🔀 beside every group chip on the Videos tab (2026-10-14),
or **Shuffle all** under the feed chips when a group is selected, loads every upload of each channel (and its Shorts, if you say so) and every episode a
podcast's feed still lists, shuffles them, puts them after what is playing as one queue group, and
starts the first. A channel asks **Include Shorts?** first. Members-only videos are left out.
Decision and reasons: [ADR 30](../adr/0030-shuffle-all-walks-every-page-into-one-queue-group.md).

## Several at once (2026-10-22)

Library → Subscriptions: tick channels or shows (long-press, then tap), and the selection bar offers **🔀 Shuffle all of
these**. It is the same walk as one source (`shuffleTargetOf` builds one target named "A + B", keyed by the sorted ids
so the same set is one pin and one recent action), asking about Shorts when a channel is among them. The 🔀 button
and this action share `rememberShuffleStarter`.

## Live streams (2026-11-20)

Past live streams are part of a channel's uploads for this purpose, so every shuffle all, one channel, a group or a
ticked selection, walks the channel's Live tab too and does not ask about it (Include Shorts stays about Shorts).
Each source's Live walk starts when its uploads walk is over, because the first Videos page tells whether the
channel has a Live tab at all; a channel without one costs no request. A podcast has none. `WholeSource`'s summary line
reads `gathered N item(s) from S source(s) in P page(s) (uploads=a, live=b, shorts=c), shorts=…, K source(s) with live
streams (M found), unfinished=…`. Decision: [ADR 178](../adr/0178-a-channels-past-streams-are-its-live-tab-found-through-its-tab-list.md).

## Seam

- `core/data/…/shuffle/WholeSource.kt`: the page walker (`SourcePages` port, `Gathered`).
- `app/…/shuffle/ShuffleAll.kt`: `AppSourcePages` (the pillar routing) and `ShuffleAll` (shuffle, group,
  running counts).
- `app/…/ui/common/ShuffleAllAction.kt`: the button, the Shorts question and the result toast. Placed
  through `SourceHeader`'s actions (channel, podcast) and the Videos feed header's group slot.

## Tests

- `WholeSourceTest` (JVM): every page to the end; Shorts only when asked; a failing page keeps what came
  before and says so; a flaky page is retried; a repeating continuation stops; a group's channels and
  podcasts merged once each; lives walked unasked, after the uploads, an empty live walk costing one page and not
  reported unfinished, a failing live walk reported with the uploads kept.
- `AppSourcePagesTest` (JVM): InnerTube paging by id; a handle resolved once then paged with its Shorts
  (observed failing first: it returned yt-dlp's 20); stored podcast episodes; a channel's live streams paged by their
  continuation, a handle resolved once for uploads and lives together, a podcast never asked for lives.
- `ShuffleAllActionTest` (instrumented): the Shorts question and both answers; a podcast with no
  question; everything lands in the queue as one `shuffle:` group.
- `ContinuationsTest` (JVM): a channel tab pages its grid, not its sort chips (`chipBarViewModel`) or the
  header's About panel (`showEngagementPanelEndpoint`). Report `20261014T184506` (0.1.610, *"was response
  truncated??"*): Peep Show and The Darkness both stopped at 30 uploads and 48 Shorts, because page 1 now
  carries those tokens after the grid's and the picker took the last. Live on 3Blue1Brown after the fix:
  152 uploads in 6 pages, 83 Shorts in 2. Channel pages scrolled past 30 by the same token.
- `GroupChipShuffleTest` (instrumented): every group chip has its own 🔀; tapping one queues that group's
  episodes as `shuffle:group:<id>` and plays one of them.
