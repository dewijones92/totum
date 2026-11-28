---
title: How a batch runs: refine, ship each feature, soak and review, report
kind: reference
updated: 2026-11-28
---

# How a batch runs

The rule itself is in `CLAUDE.md` ("How a batch runs", the four steps with Dewi's words), which every session loads and which wins if the two ever disagree; change the rule there and here in the same commit. This file is the full account: the procedure, the numbers and the incidents that earned it, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28.

- **How a batch runs** (Dewi, 2026-10-14, 2026-10-15, 2026-10-23 and 2026-11-06):
  1. **Refine the whole batch before building any of it** (Dewi, 2026-11-06: *"before we start a big batch of work, we
     refine a big batch of work … we investigate as we go, we probe … we shift left … scouts … a holistic approach"*).
     A batch mixes big items, little items and many of them, and no code is written until Dewi says so. Refining means:
     - **Read every unconsidered diagnostics report** (`tools/crashlog-server/triage.sh considered` with no date lists every one
       whatever its age, and its footer gives the server-wide count; until 2026-11-20 it hid anything older than 7 days) and turn each note into a finding or an item.
     - **Investigate and probe as you go:** settle a hypothesis with evidence, not a guess. That means a quick proof of
       concept on the real device (the LG and the webcam, a laptop server), a spike with real data (Dewi's own backup,
       tokens stripped), or the compiled code run against the real input. Several of the 2026-11-05/06 hypotheses
       changed this way.
     - **Scouts for every item, then one for the batch as a whole:** Haiku scouts (read-only) find what each item can
       reuse, so the plan names it — in this repo AND on the Pi (its running services, `systemctl --user`, containers, and the
       `dot-files` repo: on 2026-11-06 the speaker queues turned out to duplicate `hermes-audio-ctl`, which no scout had looked
       at); then one review reads every item together for shared seams, items to merge and
       items that would copy existing code, so each seam is built once and first. Check a scout's claim before you
       repeat it.
     - **Ask Dewi only what only he can answer** (scope, defaults, trade-offs), a few at a time with a recommendation
       each; never facts the code can answer.
     - **Write it down:** one `docs/todos/<item>.md` per item with its decision, evidence and reuse; an overview doc
       with the phases, their order and what was declined; the session's task file, so it survives a context reset.
  - **Dewi's real app state is a lens on everything** (Dewi, 2026-11-14: *"I want my app state to be one of the lens that we
    do everything as this is real world usage"*). The newest state dump (Settings → send my state, or every report; the Pi keeps
    five, ADR 159) is copied to `~/claude-tasks/state-dumps/` (600, redacted, never committed or sent anywhere). Refining reads it
    for the real shapes and sizes (on 2026-11-14: 5,857 queue items, 1,623 subscriptions, 509 downloads, 1,207 progress records);
    every builder brief names it and asks how the change behaves at that scale and with those contents; every batch ends with a
    pass on the emulator loaded with it (`tools/emulator/load-state.sh`), after the clean soak, looking at the changed screens and
    timing what scales with it (opening the queue, start-up, search, groups). **Take a fresh dump before every big batch** (Dewi,
    2026-11-20): tap the Quick Settings tile **📦 Send my state** on the phone, then run `tools/state/pull-state.sh`, which copies
    the Pi's newest dump into `~/claude-tasks/state-dumps/` (600), prints its app version and age and warns past 3 days.
  2. **Each feature ships on its own** so Dewi can try it on his phone: tests (a bug fix's test seen red first), docs
     and ADRs in the same change, preflight and `./gradlew detekt` across all modules until clean, the changed
     tests run, a look at the changed screen on the emulator, then push and watch CI on the commit to its end (the
     full gate is CI's job; see Build & test). No soak per feature.
  3. **The batch ends with a soak, an Opus review and Haiku sweeps in parallel** (Dewi, 2026-11-01). The sweeps:
     one or two check each change for consistency across every list, way in and kind of item (tap, link,
     notification, search, restore, queue, home; the queue rows that lost their dates were this kind of miss), and
     one looks for use cases the batch missed. A sweep's finding is a hypothesis: check it against the code first.
     Then the soak (see "Before shipping a playback or
     extraction change") against the release from before the batch, and a `reviewer` agent on the batch's commits,
     given the requests and the accepted decisions. Read the whole report, check each finding against the code, fix
     what holds up with a test seen red first, and push.
  4. **Then report** in the end-of-run shape (🚧 Needs you, Changed, 🔍 Found, ⚠️ Couldn't confirm), and mark the
     phone reports the batch answered as fixed, with the first release that contains each fix.
