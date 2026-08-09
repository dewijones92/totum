---
title: Totum — agent entry point
kind: index
updated: 2026-07-25
---

# AGENTS.md

Entry point for anyone (human or agent) working on Totum (formerly UniApp). Start here, then read
what's relevant.

1. **`CLAUDE.md`** (repo root) — the binding project context: decisions, the
   quality bar (Unified + DRY twin laws), architecture, build & test commands.
   Read it first.
2. **`docs/`** — a maintained hierarchy of living documentation. See
   [`docs/README.md`](docs/README.md) for the map. In short:
   - [`docs/spec.md`](docs/spec.md) — what Totum IS and which behaviours must never
     break. Read this before deciding what to work on.
   - [`docs/architecture.md`](docs/architecture.md) — the unified seams + module map.
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
