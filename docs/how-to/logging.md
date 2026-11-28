---
title: Logging and diagnostics: log generously, provable in the wild
kind: reference
updated: 2026-11-28
---

# Logging and diagnostics

The rule itself is in `CLAUDE.md` (the two logging laws and the `dewidebug` rule), which every session loads and which wins if the two ever disagree; change the rule there and here in the same commit. This file is the full account: the procedure, the numbers and the incidents that earned it, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28.

- **Log generously — err on the side of far more.** Dewi's standing instruction
  (2026-07-28): *"would we benefit from FAR MORE THINGS being logged??? in this repo,
  always err on the side of MORE LOGS/DIAGS."* This app is debugged almost entirely
  from diagnostics reports sent off a phone that is not in front of you, so an
  unlogged decision is an unanswerable question. It has cost real time twice: a
  23-second stall was invisible because nothing recorded stalls, and "the position
  clears when I switch tabs" could not be investigated at all because tab switches
  were not recorded. When adding any branch that decides something a user would
  notice — advance or don't, skip or don't, retry or give up, use this stream or that
  one — **log the decision and the reason for it**, not just the outcome. Prefer a
  line that says why nothing happened; silence is the hardest thing to debug.
  The one real constraint is the report buffer: it holds a bounded number of events,
  so anything firing many times a second must be counted and logged periodically
  (see `SILENCE_LOG_EVERY`) rather than dropped. Counted, never silent.
- **Every change must be provable in the wild from its logs and diagnostics alone —
  this is a MUST, not a preference.** Dewi, 2026-08-06: *"we need to collect
  logs+diags verbosely to make sure what we do works in the wild … this is a MUST!!!
  and be able to be read very well after the fact"*. A feature is not done when it
  passes tests; it is done when a report sent from his phone a week later can settle
  **whether it actually worked there**. So when you ship anything, ask: *if this
  silently misbehaves on his device, which line in the next report tells me?* If the
  answer is "none", the instrumentation is part of the change, not a follow-up.
  Three rules follow, each earned:
  - **Log the inputs, not only the outcome.** A decision line that omits what it
    decided from cannot be re-judged after the fact. "skipped it" was identical whether
    a downloaded file existed or not — which is exactly how the offline-video bug
    survived (2026-08-06). Now the line carries the copy, the network and the mode:
    `route <id> -> … [handle=Video copy=audio-only offline=true listen=false]`.
  - **A report must be able to answer the obvious next question.** Report 0.1.346
    carried the whole 97-item queue and every setting, and not one word about what was
    on the disk — so "was it downloaded?" was unanswerable and the diagnosis had to come
    from reading code instead. State blocks are cheap; add the counters AND the per-item
    detail (`downloads.queueStates`), because a total cannot say whether the item that
    was *tapped* was there.
  - **Write it to be read months later by someone with no context**: name the field
    (`playing=`, `copy=`), spell the units, prefer a whole phrase over a flag, and never
    let two different situations produce the same line.
- Debug logging must be prefixed `dewidebug`. **Keep it committed** until Dewi says
  otherwise (his standing rule, which reverses the earlier strip-before-commit one):
  these lines are often useful again. Make a chatty one reasonable — log only the
  interesting case, or lower its frequency — rather than deleting it.
