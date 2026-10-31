---
title: A second app in the Totum repo, fully separate
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0001 · A second app in the Totum repo

## Context

Dewi wanted the finance hub in the Totum repo ("a thing where I put stuff now") as a monorepo, but in its own
folder so future agents can tell the two apps apart, with the root `CLAUDE.md` still followed. He also asked
that it never depend on, or be blocked by, Totum.

## Decision

- Everything lives under `aerarium/`: its own pnpm workspace, `CLAUDE.md`, `AGENTS.md`, `docs/` (ADRs,
  features, todos, tests) and tools. The root `CLAUDE.md` and `AGENTS.md` carry a short map of the two apps.
- CI is split by path: `aerarium.yml` runs on `aerarium/**` only; Totum's `ci.yml` has `paths-ignore:
aerarium/**`. Each has its own concurrency group.
- The `pre-push` hook runs each app's checks only for the files being pushed; a `pre-commit` hook runs
  Aerarium's privacy guard on staged Aerarium files.
- The separation landed on `main` first (one ordinary Totum release, because `ci.yml` changed), so every later
  Aerarium commit is invisible to Totum's pipeline.

## Consequences

- An Aerarium push never builds or releases a Totum APK, and a red Totum build never blocks Aerarium.
- `main` has no branch protection, so there are no required checks that a path filter could leave pending.
- Renovate sees both apps; Aerarium's npm updates arrive as their own PRs.
