---
title: The video page goes black when left alone
kind: adr
status: accepted
updated: 2026-10-14
---

# 48. The video page goes black when left alone

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"after a couple of seconds of me not touching the screen, I want the whole background to go
black. Currently, it's white and quite jarring"*.

## Decision

`IdleBlackout` wraps the windowed player page: while a video plays, 3 s without a touch fades everything except
the picture to black (the hole is the stage's reported window bounds). The first touch only wakes it, so it
cannot press a hidden button. Fullscreen is already black. Logged both ways under `player`.

## Consequences

- Tests: `QueueSkipAndBlackoutTest` (device: dark after 3 s, back on a touch, never while paused).
