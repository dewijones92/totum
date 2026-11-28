---
title: The queue passes over finished talk when it moves on
kind: adr
status: accepted
updated: 2026-11-27
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
- `SkipFinishedTest` covers the advance, ⏭ (`playNextInQueue`), the readied next item agreeing, the stay-in-kind stop with finished talk ahead, and the rule's exemptions. "Finished" is the rows' own account-aware state (watched to the end on YouTube counts), and the log says which ("finished here" or "finished on YouTube"), corrected after review.

## Amended 2026-11-27 (Up next shows what the queue will pass over)

- The full player's Up next listed every entry after the cursor, so "86 queued" overstated what would play (report
  20261121T061753). `skipWhy` is now the one predicate: `nextAfterThePlace` (so `peekNext`, the advance, gapless arming
  and the notification's Next line) and `upNextSkips` (the player's rows) both ask it. A row it would pass over (finished
  talk, or the other kind while auto-play stays in kind, ADR 64) is dimmed and says "✅ Finished · skipped" or "🎵 Music ·
  skipped while you listen to talk"; the header reads "📋 86 queued · 7 will be skipped". Tapping a row still plays it.
- `UpNextViewTest` pins that the first row not marked skipped is the item the advance and `NextLine` start.
