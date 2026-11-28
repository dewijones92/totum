---
title: CLAUDE.md holds the rules, and the Decisions table lives in docs/decisions.md
kind: adr
status: accepted
updated: 2026-11-28
---

# 199. CLAUDE.md holds the rules, and the Decisions table lives in docs/decisions.md

- Status: Accepted
- Date: 2026-11-28
- Amends: [ADR 1](0001-record-decisions-as-adrs.md) (the summary table is no longer in `CLAUDE.md`)
- Decided by Claude in a review Dewi asked for on 2026-11-28; he left the open choices to the reviewer.

## Context

`CLAUDE.md` had grown to 131 KB and 835 lines. Every session and every builder agent loads it, and agents were seen
reading only its first 80 to 150 lines. Those lines were the Decisions table: 131 rows, 76 KB, one added for almost every
ADR ("docs: CLAUDE.md row for ADR 198"). The laws that bind every change (the twin laws, the emulator's sign-in, the CI
cancellation rule) began at line 175 and ran to line 835, mixed with recipes and the stories of the incidents that earned
each rule.

## Decision

- **`CLAUDE.md` holds rules only**, about 39 KB, in this order: the two-apps box, hard stops, the quality bar, a
  "Read before you…" table, working agreements, the seams new work must reuse, build and test, architecture rules.
  The hard stops are the rules whose breach destroys something only Dewi can restore, costs a release, or is not the
  agent's to decide; they come first so that a reader who stops early has still read them.
- **The Decisions table is [`docs/decisions.md`](../decisions.md)**, rows verbatim. A new decision gets its ADR and its
  row there. It adds a line to `CLAUDE.md` only when every later change has to obey it: a seam for the "One seam each"
  list, a foundation changed, a hard stop, or a standing rule from Dewi. A feature decision adds nothing to `CLAUDE.md`.
- **Procedures, numbers and incident histories are in `docs/how-to/`** (`emulator`, `soak`, `room-schema`,
  `batch-process`, `logging`, `release-and-performance`), and the module notes are in
  [`docs/architecture.md`](../architecture.md). `CLAUDE.md` states the rule and wins if the two disagree; a change to a
  rule edits both in the same commit.
- **A doc that is no longer loaded automatically is required reading for its job.** The "Read before you…" table
  names the doc for each job, and a builder brief names the rows that apply.
- **No rule changed.** All 21 of Dewi's quoted sentences outside the table are still in `CLAUDE.md`. Wording changed in
  two places to keep a rule's meaning after the move: "CRUD the ADRs" names `docs/decisions.md`, and the orchestrator's
  own edits include a row in `docs/decisions.md`, as they included a row in `CLAUDE.md` before.

## Consequences

- A session loads about 10,000 tokens of rules where it loaded about 35,000 of rules, table and history.
- The seams list is a second, shorter place that names a decision (the row is the first). It carries only the seam's
  name and ADR number, so it goes stale only when a seam is renamed or replaced, and that change edits the line.
- A rule and its full account are now two files. The risk is that they drift; the how-to docs say which file wins.
- Nothing yet stops rows or histories being added to `CLAUDE.md` again. A preflight check (a size ceiling for
  `CLAUDE.md`, and no three-cell decision row in it) would; it is not built.
- Considered and not done: cutting `CLAUDE.md` to 25 KB by moving the batch digest, the consistency and duplication
  rules, the soak, schema and emulator rules or the polite-client numbers out. Each would take a rule's wording out of
  the one file every session loads.
