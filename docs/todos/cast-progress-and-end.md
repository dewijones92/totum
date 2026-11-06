---
title: Casting keeps your place and notices the end
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Casting keeps your place and notices the end

Reports 20261101T174143 ("finished on the TV, app says 70%") and 20261101T205710 (TV off, app showed Peep Show):
- `Media3PlaybackController.saveProgress` returns on a blank receiver mediaId (~1008), so nothing is saved while casting;
- `RemoteEnd.seen` is seeded only from a named item, so the TV's end is missed (no mark played, no advance);
- `PlaybackService.switchTo` caches `lastHandoffItem` only at cast start (~477-485), so the phone takes back the item
  from the start of the session;
- `GaplessArmer.due` arms at once on cast (~55);
- a spurious "ended at 0ms of -1ms" auto-advanced over a tap (cause before the buffer).
Fix each with a test seen red first; use the `CastLoadHold.unnamed()` stand-in, mindful it names what Totum last asked
for. No test covers `RemoteEnd` yet.
