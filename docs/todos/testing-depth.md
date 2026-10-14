---
title: Mutation testing and multi-step scenario tests
kind: todo
status: refined 2026-10-14 — the scenario half happened under other names; four named gaps remain (below)
area: tests
priority: medium
requested: 2026-07-25
updated: 2026-10-14
---

# Deeper testing: mutation, and multi-step scenarios

**Ask:** "maybe add things like mutation testing, and testing such as complex
multi-step scenario testing … up to you."

My honest read: **one of these earns its place now, the other doesn't yet.**

## Multi-step scenario tests — yes, do this

The bugs this session were *all* multi-step, and none would have been caught by the
unit tests that were passing:

- queue **hydration racing** an enqueue made at launch;
- an audio-only download **standing in for the video**, giving sound and a blank
  picture;
- the engine strategy **not stamping** the audio-only flag, re-opening a trap;
- the auto-play toggle hidden **behind the video gate**.

Every one needed *state built up over several actions*. So the gap is real and the
shape is clear: JVM-level scenario tests over the real seams with fakes at the edges —
"queue three things, restart, jump to the second, remove a group, advance twice, assert
the queue and cursor". Fast (no device), and exactly where the defects live. That's a
better investment than more unit tests.

A second tier worth having: a small number of **instrumented** journeys for the things
only a device shows (the Expat RSS bug, A/V desync, notification actions, fullscreen).

## Mutation testing — not yet

Pitest is the obvious tool and Kotlin/Android support is workable, but:

- it is slow enough to hurt the "gate green on every commit" loop;
- the modules where it would pay (`:core:data`, `:core:domain`) already have close
  coverage, and the recent defects were **integration** bugs, which mutation testing
  doesn't find;
- it would mostly tell us what we know — that Compose/Media3 glue is thinly covered by
  design (those modules are Kover-exempt and verified on-device).

Revisit if a *logic* bug slips through in a well-covered module — that's the signal
mutation testing is worth its runtime.

## Suggested order

1. A scenario-test harness in `:app` (fakes wired as in `FakeAppContainer`) plus the
   first few queue/download journeys.
2. Extend to cross-pillar journeys (playlist → queue → download → play → advance).
3. Only then consider Pitest on `:core:*`.

## 2026-10-14: where this stands, and what is left

**The status said "refining" for eleven weeks while most of it was done.** The multi-step scenario tests
this item asked for exist, written one bug at a time rather than as a harness: `:app`'s `queue/` alone has
about 25 journey tests over the real queue with fakes at the edges (`AFailedDownloadIsTriedAgainTest`,
`AStaleResolveDoesNotClobberPlaybackTest`, `ADeadStreamWithACopyPlaysTheCopyAtOnceTest`,
`GaplessQueueTest`, …), and the instrumented tier has the device-only journeys (the live phase,
`ReplayedShareIsIgnoredTest`, the queue soak). A separate harness would now duplicate them. Mutation
testing is still not worth its runtime, for the reasons above; no logic bug has slipped through a
well-covered module since.

What is actually left, each a gap named somewhere and still open:

1. **A skipped live phase must not look green.** CI's live tests skipped silently from 2026-08-01 to
   2026-10-08 (a stale home-IP secret) while every run passed; the hour-long stall and the SABR
   served-nothing failure were there the whole time. Make a skipped live phase visible: a job summary line
   with the reason, and a red build after N consecutive skips on `main` (N to agree with Dewi). Highest
   value of the four.
2. **`MainActivity`'s notification-permission call site** (named in `docs/tests/_index.md`): nothing routes
   through it, so re-inverting its `if` would be green everywhere.
3. **The rewind chain in one test**: covered in two halves (`RewindIsRememberedTest`,
   `AccountResumeSurvivesTheProcessTest`); accepted layering, recorded so nobody reads it as whole. Leave.
4. **Account writes against a real account** (music favourites, ADR 32): covered by fakes and by a manual
   live check on 2026-10-14. A live test would write to Dewi's real account, so it stays uncovered unless a
   throwaway test account exists; ask him.

Done when: (1) is built and agreed; (2) has a test that fails on the inverted `if`; (3) and (4) are recorded
as deliberately uncovered in `docs/tests/_index.md`.
