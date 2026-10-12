---
title: An out-of-date build says so on every tab
kind: adr
status: accepted
updated: 2026-10-12
---

# 29. An out-of-date build says so on every tab

- Status: Accepted
- Date: 2026-10-12

## Context

Dewi, 2026-10-12, after a report arrived from 0.1.602 while 0.1.603 was already released: *"add in
the app some sort of IN YOUR FACE telling me that the app is out of date"*. Updates come through
Obtainium, which checks in its own time, so a phone can sit on an old build while fixes are out.
Reports from that build cost triage time on defects already fixed.

## Decision

- `AppUpdateCheck` (`:core:data`, pure JVM) compares `BuildConfig.VERSION_NAME` with the repository's
  GitHub "Latest release" (`api.github.com/repos/dewijones92/totum/releases/latest`, the tag
  `v0.1.<run>` that every push to main publishes). Versions compare numerically, part by part.
- It checks at launch and every 30 minutes while the process lives (`TotumApplication`), on
  `transferClient`, so the busy bar never flashes for it. GitHub allows 60 unauthenticated requests
  an hour per address; this makes 2.
- A failed lookup keeps the last verdict: offline does not make an old build current.
- A build CI did not number (`0.1.0-dev`: local, emulator, every test run) is `NotApplicable` and
  never asks GitHub.
- When out of date, a red (`colorScheme.error`) banner is the shell's `topBar` on every tab: *"Totum
  is out of date — You're on vX. vY is out."* with **Update**, which opens Obtainium if installed,
  else the release page. It cannot be dismissed. It goes away when the update is installed.
- Every report carries `update.installed`, `update.status`, `update.checkedAtMs` and
  `update.lastProblem`, so triage can see at a glance whether the build was already stale.

## Consequences

- The banner is not drawn over the full player or the Shorts reel, which are full-bleed video.
  Leaving the player shows it.
- A release that CI publishes before Obtainium sees it makes the banner appear first; Update then
  opens Obtainium, which may need a manual refresh.
- One network call per half hour, from a background scope, never blocking startup.
