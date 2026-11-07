---
title: What's next, in the notification
kind: todo
status: built 2026-11-06 (tests written 2026-11-07)
batch: A (2026-11)
priority: low
updated: 2026-11-07
---

# What's next, in the notification

Dewi 2026-11-06: "Next: …" under the current item. core/playback cannot read the queue, so the app passes the next
title down; it must refresh on queue edits, not only on item change.

## Built (2026-11-06)

See [next-in-notification](../features/next-in-notification.md) and [ADR 131](../adr/0131-a-play-carries-its-reason-and-the-notification-says-whats-next.md).
The line rides `PlaybackContext`; the rules mirror the advancer's order and end in `nextAfterThePlace`, so it names
what actually plays next.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`NextLineTest` (21: queued, the shield's title, nothing playing / nothing after / auto-play off, repeat one, the sleep
timer outranking everything, explore, repeat queue and group with their reshuffled rounds, items the advance passes
over, stay-in-kind, and the context the notification is sent), instrumented `TheNotificationSaysWhatIsNextTest`
(`:core:playback`: the real provider's text for next, no artist, held back for another item, repeat one, explore, live
badge then next, a refresh once per change, the context bundle round trip; reads the protected text by reflection).
Seen failing with the sleep-timer rule inverted.

Still owed: a device check that reordering the queue changes the line within a second and that the lock screen shows it.
