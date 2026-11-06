---
title: Already queued? Say so
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Already queued? Say so

Dewi 2026-11-06: adding something already queued says "already queued at #N, move it up?". Today `enqueue`/`playNext`
move it silently and `addToGroup` skips silently (`QueueSnapshotOps.relocating`); the prompt replaces both.
