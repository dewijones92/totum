---
title: A morning digest of what's new
kind: todo
status: built 2026-11-07, Batch A (tests owed)
batch: A (2026-11)
priority: low
updated: 2026-11-07
---

# A morning digest of what's new

Dewi 2026-11-05 (agreed idea): one notification each morning saying in two lines what's new across his groups since last night, built on group news (ADRs 94/95) and the per-group notification switches (ADR 118); dry runs.

Whole-batch review: built with the For you feed as one morning job and one notification. Dewi 2026-11-06: with the digest on, the per-video "new upload" notifications stop and the digest replaces them; urgent ones (alarms, Exsurge, downloads, the Pi's health) still come through. Record it against ADR 118.

## Built 2026-11-07

Built with For you as one morning job ([feature](../features/morning-job.md)): with the digest on, a new video in a group
no longer notifies; it goes into a small inbox (`morning.json`), and at about 07:30 one notification gives two lines
written by Haiku (a Pi task `morning-digest`, no tools; titles hidden by the spoiler shield stay hidden), or plain lines
when the Pi cannot be reached. A group's bell still silences it. Recorded against [ADR 118](../adr/0118-every-new-content-notification-can-be-switched-off.md).
My call: only the per-video **group** notifications are replaced; the subscription "new uploads/episodes" notifications
(also ADR 118) are untouched, because they are not "across his groups" and would have nothing to replace them.

Decisions, dry runs and limits: [ADR 143](../adr/0143-for-you-and-the-morning-digest-are-one-morning-job.md); the feature doc: [morning-job.md](../features/morning-job.md).

## Tests owed

- `MorningRunTest` covers the digest (hidden titles sent hidden, the model's lines, plain lines when the Pi is down, nothing
  new makes no call, an old entry is not told again, the inbox cap and age); owed: `MorningRules.tidyDigest` and
  `fallbackDigest` beyond the CLI test, `MorningStore` (file round trip, `consumed`, `pickedLately`), `groupNewsNotify`
  (digest on adds to the inbox and returns true; digest off still notifies; a quiet group never reaches it), the
  notification text (`morning_picks_line` plural, "🆕" prefix once).
- A device check: switch the digest on, let a group get a video, run the job from Settings, see one notification and no
  per-video one.
