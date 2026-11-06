---
title: What's next, in the notification
kind: todo
status: built 2026-11-06 (code first; tests to follow)
batch: A (2026-11)
priority: low
updated: 2026-11-06
---

# What's next, in the notification

Dewi 2026-11-06: "Next: …" under the current item. core/playback cannot read the queue, so the app passes the next
title down; it must refresh on queue edits, not only on item change.

## Built (2026-11-06)

See [next-in-notification](../features/next-in-notification.md) and [ADR 131](../adr/0131-a-play-carries-its-reason-and-the-notification-says-whats-next.md).
The line rides `PlaybackContext`; the rules mirror the advancer's order and end in `nextAfterThePlace`, so it names
what actually plays next.
