---
title: Periodic jobs and their last run
kind: feature
status: built 2026-11-06 (tests to follow)
area: platform
updated: 2026-11-06
---

# Periodic jobs and their last run

Batch A's shared seam ([ADR 129](../adr/0129-periodic-jobs-remember-their-last-run-and-data-is-counted-from-the-uid.md)).
`PeriodicJob(name, interval, network)` (`app/.../jobs/PeriodicJob.kt`) schedules a unique periodic WorkManager job and
runs it: a start line, a `JobOutcome` (`Done(detail)`, `Failed(reason, retry)`, `Skipped(why)`) mapped to the result,
a thrown exception recorded as a failure, cancellation rethrown. `LastRuns` (`LastRun.kt`) persists per job when it
last ran, whether it succeeded, a detail line and when it last succeeded; `Context.agoText` is the one formatter for
"2 days ago".

| Job (unique name) | Worker | Every | Network | Records |
|---|---|---|---|---|
| `new-content-refresh` | `NewContentWorker` | 6 h | connected | items notified, or why it failed (retry) |
| `new-uploads` | `NewUploadsWorker` | 2 h | unmetered | done (skipped under instrumentation) |
| `group-news` | `GroupNewsWorker` | 60 min | connected | done (skipped under instrumentation) |
| `queue-tidy` | `QueueTidyWorker` | 7 days | none | what moved, see [queue-tidy-up](queue-tidy-up.md) |
| `backup-check` | `BackupCheckWorker` | 7 days | connected | which copy read, or why not, see [backup-checked](backup-checked.md) |

The three older workers kept their unique names, intervals and constraints, so existing scheduled work is kept.

## Diagnostics

`jobs` lines: `<name> scheduled every N min`, `<name> starting (every N min, last ran …)`, `<name> skipped: …`,
`<name> threw`, and `dewidebug <name> succeeded|FAILED: <detail> [previous=… lastSucceeded=…]` on every recorded run.

## Adding a job

A `PeriodicJob` constant, a worker whose `doWork` is `JOB.run(container.lastRuns) { … }`, one `schedule` line in
`TotumApplication`, and a row that reads `LastRuns.observe(name)`.

## Tests still to write

`PeriodicJob.run` mapping (done, failed with and without retry, skipped, thrown, cancelled); `LastRun.encoded`/
`decoded` round trip including a detail with `|`; `LastRuns.succeeded`/`failed` keeping the last good time;
`agoOf` boundaries (59 s, 1 min, 59 min, 1 h, 23 h, 1 day).
