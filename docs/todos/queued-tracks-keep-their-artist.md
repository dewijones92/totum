---
title: Queued tracks keep their artist
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Queued tracks keep their artist

Found 2026-11-06 by the ✨ Ask spike on Dewi's Full backup: 1,965 of 5,838 queue items have no `author`, about 420
of them Chopin album tracks, so neither the rows nor Ask can say who plays them. Against ADR 108's "every path that
builds an item fills what is known". Decided: find the queueing paths that drop the artist (album and artist pages,
radio, shuffle all, pins), fill it in, and repair existing rows once (from the album, the source or a lookup), with a
test per path seen red first.

Whole-batch review: the one-off repair extends `SharedMetadataRepair` (already retries titles and dates); the fill goes into the queue entry seam, not each caller.
