---
title: Aerarium docs
kind: index
updated: 2026-10-31
---

# Aerarium documentation

| Dir                               | What                                      | Convention                    |
| --------------------------------- | ----------------------------------------- | ----------------------------- |
| [`adr/`](adr/_index.md)           | Architecture decision records             | `NNNN-title.md` + `_index.md` |
| [`features/`](features/_index.md) | One doc per feature: status, files, tests | `<feature>.md` + `_index.md`  |
| [`todos/`](todos/_index.md)       | The live backlog                          | `_index.md`                   |
| [`tests/`](tests/_index.md)       | Testing strategy and coverage map         | one file                      |

Every doc starts with YAML frontmatter carrying `updated: YYYY-MM-DD`; bump it when you touch the doc.
`pnpm preflight` checks the frontmatter and that every ADR, feature and e2e spec is indexed.
