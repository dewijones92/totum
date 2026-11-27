---
title: Play next stacks in the order you chose it
kind: todo
status: built 2026-11-27, Batch C task V (ADR 195; unit tests on the phone queue and on a Pi target; not seen on the phone yet)
priority: medium
updated: 2026-11-27
---

# Play next stacks in the order you chose it

Dewi, 2026-11-27: he downloads a few videos and podcasts in the morning and sets the day's order with Play next on A, then B,
then C; they came out C, B, A. Decision and design: [ADR 195](../adr/0195-play-next-stacks-in-the-order-you-chose.md).
Feature: [playback-queue](../features/playback-queue.md#play-next-stacks-adr-195).

## Reuse

`QueueSnapshot` already carried the queue's place; the chain is one more small field on it. `insertingAllNext` (the phone's
bulk path and a Pi target's `NEXT`) became the one place the rule lives; `relocating` (Move up), `movingGroup` (a group
header's Play next) call the same `placingNext`. The restart value rides in `totum_queue_place`, the file that keeps the
queue's place.

## Owed

- Look at it on the phone with the real queue (about 5,857 items): the rule is a few list operations per tap, not per frame.
- A single Play next on an item that is already queued still shows the "already queued, move it up?" offer while the queue-row
  and bulk paths move it at once. That split is older than this change (ADR 108 territory); it was left alone.
