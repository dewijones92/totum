---
title: The queue passes over finished talk when it moves on
kind: adr
status: accepted
updated: 2026-10-22
---

# 74. The queue passes over finished talk when it moves on

- Status: Accepted
- Date: 2026-10-22

## Context

Dewi, 2026-10-22: skip items already watched or listened to when the queue moves on; one started and not finished
still plays from its place. A queue of 500 with groups of 150 means the next item is often one already finished,
here or on the YouTube account.

## Decision

- Moving on (auto-advance, ⏭ and the next item readied for gapless) passes over a talk item whose state is
  `Played` (`passOverReason`, one rule, `PlaybackQueue.passOver`). Going back (⏮) does not; a tap on a row always plays
  it.
- Music is never passed over: a played song is not "done".
- While any repeat is on, nothing is passed over: a repeated group's second round would otherwise skip everything it
  played in the first.
- The advance line names what it passed over ("passed over 2 finished: …"), and "nothing playable after" says so when
  every remaining item was finished.

## Consequences

- No setting yet; asked for as a behaviour. A finished item stays in the queue, dimmed as before, and plays on a tap.
- `SkipFinishedTest` covers the advance, ⏭ and the readied next item agreeing, and the rule's three exemptions.
