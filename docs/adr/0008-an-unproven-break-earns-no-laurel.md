---
title: An unproven break earns no laurel
kind: adr
status: accepted
updated: 2026-10-01
---

# 8. An unproven break earns no laurel

- Status: Accepted (Claude's call, 2026-10-01; open to Dewi changing it)
- Date: 2026-10-01

## Context

If no steps arrive within the rise timeout (3 minutes by default) after GO, the break starts
anyway, so a quiet sensor cannot trap him in "Rising". The Opus review on 2026-10-01 showed the
cost: pressing GO and never standing still earned a full laurel and kept the streak. That defeats
"the 5-minute timer starts after ~20 steps".

## Decision

- An outcome records `stepsRequired`: true when a working step counter was listening at GO.
- A break is **credited** (laurel, streak, rank, "done" in the tally) only when it was completed
  **and** either the steps were proven or no steps were required. A completed but unproven break
  counts as `unproven`, and it breaks the streak like a skip.
- "Steps available" means a step listener is actually registered (`ExsurgeBannerService.
  countingSteps`), not just that the permission and the hardware exist. Without one, GO starts the
  break at once and the break is credited.

## Consequences

- Dodging is no longer rewarded, and a phone with no sensor is not punished.
- Records written before this change have `stepsRequired=false`, so they stay credited.
