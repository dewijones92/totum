---
title: The auto-downloader counts every skip, and restarts a download that says it is running when nothing is
kind: adr
status: accepted
updated: 2026-11-27
---

# 197. The auto-downloader counts every skip, and restarts a download that says it is running when nothing is

- Status: Accepted
- Date: 2026-11-27

## Context

The nightly CI investigation of 27 Nov 2026 ran `SeveralSharedLinksAllLandTest` straight after
`AnHourLongItemDoesNotRebufferTest` on the emulator. The fetch half failed: two shared videos sat in
`Downloading` at 0 MB for five minutes, with no `start` and no `tee` line in the report. Nobody could tell
whether something was fetching them or whether a stale row was left over.

Two silences made that unanswerable:

- `QueueAutoDownloader.skipReason` returned an empty reason, which means "never logged", for a local file, an
  item already on the phone, an item `Downloading`, an item held while it streams and an item with no fetch
  address yet. Of those only the streaming one logged anything.
- `DefaultDownloadManager.download` answers "already being downloaded" from the store row alone. A row left
  `Downloading` with no coroutine behind it is therefore never moved by anything: the downloader skips it as
  in flight, and the manager refuses to start it as already running. The start-up pass has always repaired such
  rows for what the app died during (ADR 80), but nothing repaired one at run time.

The manager can say what is really in flight: it keeps the coroutine of every download it started in `running`,
under a lock, and `fetch` writes the `Downloading` row and registers the job inside that same lock hold. So a
`Downloading` row with no entry in `running` is a stranded one, with no guesswork.

## Decision

- **Every skip is counted, once per pass, in one line.** Each skip carries a label, and a pass ends with
  `dewidebug pass skips: 6120 already on the phone, 40 in a group not downloaded automatically, 3 downloading
  with 0 bytes so far, ...; 6163 skipped, 1 asked for, 6164 queued`. It is logged only when it differs from the
  previous pass's line, because the queue emits twice for one edit and the steady state would otherwise repeat
  itself. A skip with something to say still gets its once-per-item line, as before. A queue of 6,164 finished
  items is one line.
- **`DownloadManager.recoverStranded()`** is the start-up pass made callable at any time, and the start-up pass
  now calls it. For each `Downloading` row that has no job in `running` and is not being cancelled or deleted,
  it restarts the download as it was asked for, or leaves it `Failed` for Retry when the network is metered or
  it has already had two fresh starts (ADR 80), or drops the row when nothing records what it was. It returns
  what it did. Whole runs are serialised and each row is re-checked under the running lock just before it is
  touched, so the start-up pass and the downloader's pass cannot both resume one row and spend its attempts
  twice. `cancel` and `delete` mark an item as stopping from the moment they take its job until its row is
  gone, so a download winding down is never mistaken for a stranded one.
- **The downloader calls it at the start of a pass, after the network gate, and only when some row is
  `Downloading`.** It then re-reads the states. A pass over a queue with nothing downloading costs nothing
  extra. When it finds anything it logs `dewidebug stranded downloads: N row(s) said Downloading with no
  download running; restarted 1 [id]; left failed for Retry 0; dropped for want of a record 0`, naming at most
  five ids per outcome. A restarted item shows as `Downloading` in the same pass's skip line, and one left
  `Failed` goes through the downloader's ordinary transient retry budget.
- The log lines the manager already wrote for an interrupted download keep their wording about the app
  stopping, which is what usually strands a row; the stranded line above is the one that says it happened at run
  time.

## Consequences

- A report now answers "was it really downloading?" from two lines: the pass summary says how many items were
  `Downloading` with nothing received, and a stranded row is named with what became of it.
- A row stranded by a fetch that ended without a terminal state is restarted at most twice, then left for Retry,
  instead of blocking the item for the life of the process.
- Not done: a live download that is stuck at 0 bytes is counted in the summary but not aged, since the state
  carries no time; the downloader's own lane still logs `gave up waiting` after ten minutes. A pass runs only when
  the queue or a group choice changes, so a stall with no queue activity is not re-examined until something
  changes.
- Related: [ADR 80](0080-choose-episodes-to-download-and-resume-interrupted-ones.md) (the at-most-twice resume),
  [ADR 186](0186-one-fetch-per-item-a-playing-stream-is-its-own-download.md) (one fetch per item).
