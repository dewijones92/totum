---
title: The queue says whose each run is, and which group is playing
kind: adr
status: accepted
updated: 2026-10-16
---

# 63. The queue says whose each run is, and which group is playing

- Status: Accepted
- Date: 2026-10-16

## Context

Items added by hand were queued ungrouped, but drawn straight after a group with no heading of their own, so they read as
part of it ("I don't want it added to e.g. Peep Show group obviously"). And the playing group's header said only
"playing", easy to miss; Dewi asked for it to be "really obviously conveyed … like emoji".

## Decision

- A run of ungrouped items in a queue that has groups gets a "➕ Added by you · N" heading (`UngroupedHeader`), drawn
  only where a run starts. A queue with no groups shows none. This is display only: nothing about grouping changes.
- The group holding the playing item has a tinted header (primary container) titled "▶️ <group>" with a second line,
  "🔊 Playing now" or "⏸️ Paused here" (`queue-group-playing`). A collapsed group keeps saying so.

## Consequences

- The playing header is a line taller, which `QueueGroupCollapseTest` met at CI's 360×640 dp: its third row is now
  scrolled to before it is asserted.
