---
title: Record decisions as ADRs
kind: adr
status: accepted
updated: 2026-11-28
---

# 1. Record decisions as ADRs

- Status: Accepted
- Date: 2026-10-01

## Context

Totum's decisions lived in one table in `CLAUDE.md`. A row holds the choice and a sentence of why,
which is right for a summary and too small for a decision with real consequences: the alternatives
weighed, the constraints that forced it, and what has to be true for it to stay right. Dewi asked,
on 2026-10-01, for a rule to *"remember to CRUD any ADR"*.

## Decision

- Decisions are recorded in `docs/adr/`, one file each, numbered.
- Making, changing or reversing a decision creates, updates or supersedes its ADR **in the same
  change**, exactly like the rest of the living docs.
- The `CLAUDE.md` Decisions table stays as the summary and links to the ADR where one exists.
  (Amended 2026-11-28 by [ADR 199](0199-claude-md-holds-the-rules-and-the-decisions-table-lives-in-docs.md): the table is now
  [`docs/decisions.md`](../decisions.md); it is still the summary.)

## Consequences

- The why of a decision outlives the conversation that made it.
- Earlier decisions are not backfilled. Each gets an ADR when it is next touched.
