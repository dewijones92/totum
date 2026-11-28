---
title: Totum — agent entry point
kind: index
updated: 2026-11-28
---

# AGENTS.md

**This repo holds two apps.** Totum (this file, the root) and **Aerarium**, the finance hub under
[`aerarium/`](aerarium/AGENTS.md), which has its own entry point, `CLAUDE.md` and `docs/`. If your work is in
`aerarium/`, start at [`aerarium/AGENTS.md`](aerarium/AGENTS.md). The root `CLAUDE.md`'s general rules bind both.

Entry point for anyone (human or agent) working on Totum (formerly UniApp). Start here, then read
what's relevant.

1. **`CLAUDE.md`** (repo root) — the binding rules: the hard stops, the quality bar
   (Unified + DRY twin laws), the working agreements, the seams new work must reuse,
   build & test commands and the architecture rules. Read all of it first; its
   "Read before you…" table says which doc below is required for which job.
2. **`docs/`** — a maintained hierarchy of living documentation. See
   [`docs/README.md`](docs/README.md) for the map. In short:
   - [`docs/spec.md`](docs/spec.md) — what Totum IS and which behaviours must never
     break. Read this before deciding what to work on.
   - [`docs/architecture.md`](docs/architecture.md) — the unified seams + module map.
   - [`docs/decisions.md`](docs/decisions.md) — every standing decision, one row each (search it, do not
     read it whole); the ADRs in [`docs/adr/`](docs/adr/_index.md) are the authority.
   - `docs/how-to/` — procedures and the incidents behind them: `emulator`, `soak`, `room-schema`,
     `batch-process`, `logging`, `release-and-performance`.
   - [`docs/features/`](docs/features/_index.md) — one doc per feature (status, seam, files, tests).
   - [`docs/todos/`](docs/todos/_index.md) — the live backlog, one file per item.
   - [`docs/tests/`](docs/tests/_index.md) — testing strategy + coverage map.
   - [`docs/features/command-line.md`](docs/features/command-line.md) — the desktop front end,
     which shares the app's extraction stack and must not grow a second copy of it.

## Keep the docs current (part of "done")

These docs are **living** — a change isn't done until the docs that describe it
are updated in the same pass:

- Ship or change a feature → update its `docs/features/<name>.md` (and the index).
- Start / finish / drop a backlog item → update `docs/todos/`.
- Add or move test coverage → update `docs/tests/_index.md`.

Every doc carries YAML frontmatter (`status`, `updated`, …). Bump `updated` when
you touch a doc. This mirrors the DRY law: a fact lives in one place, and that
place is kept true.
