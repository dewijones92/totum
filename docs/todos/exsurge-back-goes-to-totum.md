---
title: Back on the Exsurge page goes to Totum (the player if something is loaded)
kind: todo
status: agreed 2026-10-07, not started
area: side-quest
priority: low
requested: 2026-10-07
updated: 2026-10-07
---

# Back from the Exsurge page lands in Totum

Dewi, 2026-10-07: *"pressing back in the exsurge page needs to go on to the totum app, maybe in the
currently playing?"*

This reverses the 2026-10-01 behaviour in [features/exsurge-et-disce](../features/exsurge-et-disce.md)
("opens in its own task, out of Recents, so Back returns to whatever was in front").

## Agreed

- Something playing **or paused** (an item loaded): Back opens Totum's full player.
- Nothing loaded: Back opens Totum's main screen (its last tab).

## Open

- Does the same apply when the page was reached from the takeover, or only from the banner and tile?
- Keep the page out of Recents?

## Done when

Back from the page reaches the player with an item loaded and the main screen without one, with an
instrumented test for each, a `dewidebug exsurge back ->` log line naming the destination and why, and
the feature doc and its ADR updated.
