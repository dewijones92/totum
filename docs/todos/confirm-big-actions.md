---
title: Confirm big destructive actions
kind: todo
status: done 2026-10-22
area: queue/ui
priority: high
requested: 2026-10-22
updated: 2026-11-06
---

# Confirm big destructive actions

Dewi accidentally cleared his queue (report 20261018T155620-d7991322): clearing the queue, deleting many downloads, removing a group and the like ask first (with Undo where it already exists).

## Amended 2026-11-06

Clearing the play history and deleting playlists no longer ask: they offer ↩️ Undo (the coordinator's call; Dewi delegated
it, [ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md)). Clearing the queue, deleting
downloads and removing a group keep their hold to confirm.

