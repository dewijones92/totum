---
title: Mark diagnostics reports as triaged/fixed on the server
kind: todo
status: shipped
area: diagnostics
priority: high
requested: 2026-07-28
updated: 2026-10-02
---

# A report should record whether it has been dealt with

**Ask (Dewi, 2026-07-28):** "maybe put a bool FIXED column on the diag db?"

The crash/diagnostics store on the Pi is append-only files under
`/data/reports/<date>/<timestamp>-<id>.json`, with no notion of whether anyone has
looked at a report, let alone acted on it. On 2026-07-28 that cost something real: 27
reports had accumulated and **26 had never been opened**, including the two most recent —
which turned out to hold a live crash (tapping Cast killed the app) and the actual cause
of the "buffering lots" complaint (an expired stream URL retried seventeen times). They
were found only because Dewi asked whether any dumps were unread.

An unread flag alone would have surfaced those. A triage state does more, because the
other half of the same session's lesson was that **two of the five findings were already
dead** — a PyException fixed 28 builds earlier, and a "crash" that was just `adb` killing
the app during testing. Knowing that a signature has been judged before, and what the
judgement was, is what stops it being re-investigated.

## Shape

Minimum: a mutable `fixed` boolean per report, settable without rewriting the file.

Better, and barely more work — a small sidecar (SQLite, or a `triage.json` beside the
report) keyed by report id, holding:

- `state` — `new` | `triaged` | `fixed` | `wontfix` | `noise`
- `fixedIn` — the version the fix landed in, so a recurrence *after* that version is
  obviously a regression rather than a duplicate
- `note` — one line of why, so the next session inherits the reasoning
- `signature` — exception + top app frame, so repeats group instead of listing

`noise` matters as much as `fixed`: the CrashedByAdb reports are self-inflicted and
should never cost anyone attention again.

## Why it earns its place

- A "N unread" count is the thing that would have made 26 unread reports visible.
- Grouping by signature turns "11 crashes" into "one bug, 11 times, on one build".
- `fixedIn` makes the version question — the one that killed two of five findings — a
  property of the data instead of an investigation every time.

## Related

- `docs/todos/crash-reporting.md` — the reporting pipeline this sits on top of.
- The global rule "Date every artefact" now requires the version pass; this is the
  server-side half that makes it cheap.

## Shipped and deployed (2026-07-28)

Built on the existing SQLite index rather than a sidecar — the server already had one, which
made this much cheaper than the sketch above assumed.

Four columns (`state`, `fixed_in`, `note`, `triaged_at`) with an idempotent
`ALTER TABLE ADD COLUMN` migration, tested against a copy of the live pre-triage schema
before deploying: columns added, safe on a second run, existing rows default to `new`.

Endpoints: `POST /api/report/{id}/triage`, `POST /api/triage/signature` (judge a whole group
at once — reports arrive in elevens, and triaging one at a time is how they stay untriaged),
and `GET /api/unread` which groups by signature and lists the versions each group spans. The
index page shows an unread count and a state chip per row.

**Deployed to the Pi and used immediately**, which is the real test:

```
before   33 unread, nothing distinguishable
after    12 unread — all diagnostics reports, no crashes outstanding
         14 fixed, 7 noise
```

The grouping justified itself on first contact: the 11 PyExceptions collapsed to one row on
one build (`0.1.142`), which is exactly the "one bug, 11 times" reading that was invisible
before. `noise` accounted for 7 — deployment smoke tests and two crashes that were my own
emulator, all of which had previously looked like real reports.

Backup at `/home/pi/crashlog-data/index.db.pre-triage-backup`; the pre-deploy image is still
on the Pi if a rollback is ever needed.

**Gotcha for next time:** the database is `index.db`, not `reports.db`. The first backup
attempt silently copied nothing, and only saying "backed up" after checking the listing
caught it.

## Findings, one verdict each (2026-10-02)

Dewi asked to *"flag diag items as considered"*. One verdict per report had stopped being enough:
the 0.1.555 report held a real bug, a September crash still sitting in the logcat buffer, and an
unavailable-video sync line. Those are three separate verdicts.

So a report now holds **findings**, each with its own state. A report is considered when they are
all judged. Dewi chose the details:
- per finding, not per report;
- set through the API (`tools/crashlog-server/triage.sh`), with no web buttons;
- everything received before 2026-10-01 bulk-marked `triaged`, as an unreviewed backlog, so
  that `new` means new again.

See the server README.
