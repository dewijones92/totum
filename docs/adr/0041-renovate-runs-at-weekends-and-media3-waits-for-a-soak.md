---
title: Renovate runs at weekends, merges only its own green PRs, and Media3 waits for a soak
kind: adr
status: accepted
updated: 2026-10-14
---

# 41. Renovate runs at weekends, merges only its own green PRs, and Media3 waits for a soak

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"auto update deps in this repo??? with automated merging on github??"*. Renovate (the
hosted Mend app, `renovate.json`) had been installed since 2026-07-13 with auto-merge for patch and minor
updates, and had **never opened a pull request**: the Dependency Dashboard (issue #1) listed nine updates
"Awaiting Schedule". The schedule was `before 6am on monday`, a six-hour window the hosted app's runs kept
missing (its last run before this was a Monday at 17:57 UTC).

`main` has no branch protection and GitHub's auto-merge is off. Every merge to `main` publishes an APK to
Dewi's phone.

## Decision

- `schedule: every weekend`, so a run cannot miss the window, and dependency builds land at most once a week.
- `platformAutomerge: false`: Renovate merges a PR itself, after its checks pass. GitHub's own auto-merge
  would not wait for CI on an unprotected branch.
- **Media3 is its own group, never auto-merged, labelled `needs-soak`**: it is the playback engine, and a
  playback change ships only after a soak against the build before it (CLAUDE.md).
- Kept: Kotlin + KSP, AGP, Chaquopy, FFmpeg and QuickJS are held for review; majors are never auto-merged.

## Consequences

- The nine waiting updates are opened by ticking "Create all awaiting schedule PRs" on the dashboard after
  this lands.
- An auto-merged bump arrives as an ordinary release, so its effect shows in the next diagnostics report
  like any other change.
