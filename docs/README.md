---
title: Totum docs
kind: index
updated: 2026-11-28
---

# Totum documentation

Living documentation for Totum — the single Android app replacing PipePipe
(YouTube) and AntennaPod (podcasts) with one unified domain model. `CLAUDE.md`
(repo root) holds the binding rules and quality bar; these docs hold the decisions, the
procedures and the detail, and keep them current.

## Layout

| Dir | What | Convention |
|---|---|---|
| [`spec.md`](spec.md) | What Totum IS, and the invariants of its core loop | one file |
| [`architecture.md`](architecture.md) | The unified seams + module map | one file |
| [`decisions.md`](decisions.md) | Every standing decision, one row each (the table that was in `CLAUDE.md`) | one file; a new row per decision, linking its ADR |
| `how-to/` | Procedures, numbers and the incidents behind a `CLAUDE.md` rule (emulator, soak, Room schema, batch, logging, releases) | `<subject>.md`; `CLAUDE.md` holds the rule and wins |
| [`features/`](features/_index.md) | One doc per feature — status, seam, files, tests | `<feature>.md` + `_index.md` |
| [`todos/`](todos/_index.md) | The live backlog — one file per item | `<slug>.md` + `_index.md` |
| [`tests/`](tests/_index.md) | Testing strategy + coverage map | one file |
| [`drafts/`](drafts/subreddit-post.md) | Text drafted for Dewi to edit and post himself; never posted by Claude | `<subject>.md` |
| [`adr/`](adr/_index.md) | Architecture decision records — one per decision | `NNNN-title.md` + `_index.md` |

## Frontmatter

Every doc starts with YAML frontmatter. Common fields:

```yaml
---
title: Search history
kind: feature | todo | index | reference | spec
status: shipped | in-progress | planned | dropped   # features & todos
priority: high | medium | low                        # todos
area: search | playback | channel | downloads | …
updated: 2026-07-25
---
```

## Maintenance (the one rule)

A change isn't done until its docs are updated in the same pass — see
[`AGENTS.md`](../AGENTS.md). Bump `updated`. Keep it honest: `status: shipped`
means it's on `main` and verified.
