---
title: Stack — TypeScript like per-chordas, SQLite built into Node
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0003 · Stack

## Decision

React 19 + Vite, Hono on Node 24, pnpm 11, TypeScript 6.0.x strict, zod at every boundary: the same stack as
per-chordas-ad-astra (Dewi's choice), so its proven deploy pattern to the Pi carries over. Storage is Node's
built-in `node:sqlite`, so there is no native module to build for arm64 and no extra dependency.

## Consequences

One SQLite file under `/var/lib/aerarium`, backed up by the Pi's encrypted state backup.
