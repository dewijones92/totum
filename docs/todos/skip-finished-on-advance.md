---
title: Skip finished items when the queue moves on
kind: todo
status: done 2026-10-22 (ADR 74)
area: queue
priority: high
requested: 2026-10-22
updated: 2026-10-22
---

# Skip finished items when the queue moves on

When advancing, skip non-music items already marked played (finished here, or ticked on YouTube); started-but-unfinished ones still play from their place (Dewi's choice).

Done 2026-10-22: [ADR 74](../adr/0074-the-queue-passes-over-finished-talk.md). Auto-advance, ⏭ and gapless arming pass over played talk; music and any repeat mode are exempt.
