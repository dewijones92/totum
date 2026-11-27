---
title: Play next stacks in the order you chose it, behind the last play next
kind: adr
status: accepted
updated: 2026-11-27
---

# 195. Play next stacks in the order you chose it, behind the last play next

- Status: Accepted
- Date: 2026-11-27

## Context

Dewi, 2026-11-27: in the morning he downloads a few videos and podcasts, then sets the day's order with Play next on
item A, then B, then C. He wants A, B, C to play in that order. Until now every Play next went straight after the
playing item, so each one jumped ahead of the last and the order came out C, B, A. He asked for Spotify's behaviour
and said yes to replacing the old rule outright, with no extra menu option.

## Decision

- **One rule, in `:core:data`: `placingNext` (`QueueNextStack.kt`).** Play next puts the item straight after the last
  item that was put next, as long as that item is still queued, still ahead of the playing item, and the same item
  is still playing as when it was put there. Otherwise it goes straight after the playing item, as before. The item (or
  the whole run, for a group) becomes the new end of the chain.
- **The chain is remembered on the queue snapshot** as `NextStack(tail, behind)`: `tail` is where the next one goes
  after, `behind` is the item that was playing when the chain was built. Comparing `behind` with the playing item is
  what resets the chain when the playing item changes (it ended, was skipped, or was tapped over). A removed tail, or a
  tail that has been dragged behind the playing item, ends it too, because the tail is looked up when it is used.
- **Every way in goes through it.** `PlaybackQueue.playNext` and `playNextAll` (every item menu in every list,
  multi-select, search, Shorts, pins, albums and groups sent as a whole), the "Move up" offered when an item is
  already queued (`relocatingNext`), a queue group header's Play next (`movingGroup` with `NEXT`), and a Pi target's own
  queue (`TargetDeck` for `QueueMode.NEXT`, through the same `insertingAllNext`). "Play now" on a target keeps
  inserting straight after the playing item (`insertingAllAtPlace`): it starts the item at once, so it must never land
  behind a chain it would skip.
- **An item already in the chain moves to its end.** Play next on the last item of the chain changes nothing; on an
  earlier one it moves it to the end. An item queued further down joins the chain at that point.
- **It survives a restart without a Room change.** The chain is a small preferences value (`stack_tail`,
  `stack_behind` in `totum_queue_place`, the file that already keeps the queue's place), restored once the queue has
  loaded. A Pi target's chain rides in the deck's own saved state (`TargetState.nextStack`, optional, so an older Pi or
  phone ignores it).
- **Every insert is logged**: `dewidebug play-next: N item(s) [...] at #i of size (anchor X | head: why) [place=...
  playing=... tail=...]`, and `play-next chain saved`/`restoring` lines for the preferences value.

## Consequences

- Once a stacked item starts playing, the chain is over: a Play next while A plays goes after A, ahead of B and C, which is
  the "resets when the playing item changes" rule Dewi chose. A Play next made while nothing plays builds from the head.
- A single Play next on an item that is already queued still shows the "already queued, move it up?" offer (an older
  decision, unchanged); the bulk and queue-row paths move it. The offer's "Move up" now joins the chain.
- A group header's Play next with nothing playing but a remembered place (a restart) now goes after that place
  instead of the head of the queue. The old head-of-queue answer was a side effect, not a decision.

## Tests

`PlayNextStacksInOrderTest` (`:core:data`, 9: A, B, C in order; with nothing playing; reset when the playing item
changes; reset when the tail is removed; reset when the tail is behind the playing item; a group as a block; the tail
again; an earlier item moved to the end; an item from further down), `PlayNextStacksOnThePhoneTest` (`:app`, 8: the same on
the real queue plus bulk, Move up, a destination, the group header), `PlayNextChainSurvivesARestartTest` (`:app`, 3: a
restored chain is built on, none restored, one built behind another item is ignored), `TargetDeckPlayNextStackTest`
(`:cli`, 6: on a target, a group, a reset, Play now, the Pi restarting, and the wire shape staying as it was).

Seen failing against the old rule: 7 of 9 in `:core:data`, 5 of 8 on the phone and 4 of 4 on a target (the first four
target tests; the restart and wire-shape ones came later), for example
`expected:<[now, a, b, c, x, y]> but was:<[now, c, b, a, x, y]>`. The ones that passed against the old rule (tail removed,
tail behind the playing item, the chain starting again after the playing item changed, a destination being sent a play
next) are guards: the old rule also answered those the new way. `QueueBulkEditsTest`'s oracle played next one at a time
in reverse because that was how the old rule agreed with the bulk path; it now plays next in order.

`HermesAdapterContractTest` caught `TargetState.nextStack` reaching the wire as `"nextStack": null` (the adapter's recorded
`/targets` shapes are compared exactly); it is now `@EncodeDefault(NEVER)`, so the shape is unchanged until there is a chain.
