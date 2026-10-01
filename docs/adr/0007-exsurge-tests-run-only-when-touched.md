---
title: Exsurge's tests run only when its area changes
kind: adr
status: accepted
updated: 2026-10-01
---

# 7. Exsurge's tests run only when its area changes

- Status: Accepted
- Date: 2026-10-01

## Context

Dewi asked for the full pyramid for this module, *"but be smart, i dont want them running really
unless needed … i.e. code change in area"*.

## Decision

- Tests in `com.dewijones92.totum.exsurge.*` are an opt-in phase, `-Ptotum.exsurgeTests`, using the
  same mechanism as the live and audio-quality phases. `:lib:exsurge`'s Kover rule is disabled
  unless that phase is requested. Instrumented runs pass `notPackage=com.dewijones92.totum.exsurge`
  by default.
- `.github/workflows/exsurge.yml` runs the unit tier (with coverage) and the instrumented tier on
  an emulator, on a push touching the module or a seam it leans on: `PlaybackController`,
  `PlaybackInterruption`, `AppContainer`, the manifest, the settings screen and the build files.
  A run that executes zero tests fails.
- `preflight.py` fails if any file named for Exsurge, or any of those seams, would not trigger the
  workflow.
- The main CI still **compiles** everything, so an interface break is caught on every push.

## Consequences

- A change elsewhere that breaks Exsurge's behaviour without touching a guarded seam is not caught
  until the next Exsurge push. This was accepted for speed, and the seam list is the mitigation.
