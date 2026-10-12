---
title: Shuffle all (a channel, a podcast or a group)
kind: feature
status: built 2026-10-12 — not yet on the phone
area: queue/channel
updated: 2026-10-12
---

# Shuffle all

🔀 on a channel page or a podcast page, or **Shuffle all** under the feed chips when a group is
selected, loads everything that source (or every source in the group) has ever published, shuffles
it, and puts it after what is playing as one queue group. A channel asks **Include Shorts?** first.
Decision and reasons: [ADR 30](../adr/0030-shuffle-all-walks-every-page-into-one-queue-group.md).

## Seam

- `core/data/…/shuffle/WholeSource.kt`: the page walker (`SourcePages` port, `Gathered`).
- `app/…/shuffle/ShuffleAll.kt`: `AppSourcePages` (the pillar routing) and `ShuffleAll` (shuffle, group,
  running counts).
- `app/…/ui/common/ShuffleAllAction.kt`: the button, the Shorts question and the result toast. Placed
  through `SourceHeader`'s actions (channel, podcast) and the Videos feed header's group slot.

## Tests

- `WholeSourceTest` (JVM): every page to the end; Shorts only when asked; a failing page keeps what came
  before and says so; a flaky page is retried; a repeating continuation stops; a group's channels and
  podcasts merged once each.
- `AppSourcePagesTest` (JVM): InnerTube paging by id; yt-dlp for a handle; stored podcast episodes.
- `ShuffleAllActionTest` (instrumented): the Shorts question and both answers; a podcast with no
  question; everything lands in the queue as one `shuffle:` group.
