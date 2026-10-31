---
title: Downloads in three sections, and a notification per running download
kind: adr
status: accepted
updated: 2026-10-31
---

# 104. Downloads in three sections, and a notification per running download

- Status: Accepted
- Date: 2026-10-31

## Context

Dewi, 2026-10-31: *"I find the download page in the app quite difficult to use. There's like a load of
failed downloads … Make the notification for downloads better. I can only see like one at a time … I
want to see them all in progress in the progress bars … click it and go into the app, downloads …
if they're finished, go to the finished downloads … Make good decisions, just crack on."* The page
listed running, then every failure, then the finished downloads in one column; the notification was
one aggregate ("Downloading N items", the first title, an averaged bar) with no tap action at all,
and a dismissed "Downloaded" came back on the next event.

## Decision

- **The page shows one section at a time**, chosen by chips with counts: ⏳ In progress (running,
  waiting for a slot, and failures retrying by themselves), ❌ Failed (stopped for good or out of
  retries; Retry all, Clear all), ✅ On the phone (as before: sort, filter, storage, clean-up). The
  default is In progress when anything runs, else On the phone.
- **One notification per running download**, at most four (the download slots), each with its own
  bar and bytes, grouped under the "Downloading N" summary (the foreground-service notification),
  which lists them and counts what is waiting. Queued-but-not-started items stay counted, not
  notified, for ADR-era reasons: the queue auto-downloads everything in it.
- **Every download notification opens Library → Downloads at its section** through one request
  (`DownloadsRequests`): the shell switches to the Library tab and the Library opens Downloads.
- "Downloaded" and "failed" notifications are re-posted only when their list changes.

## Consequences

- Up to five download notifications at once (four running plus the summary) instead of one.
- A failure retrying by itself is not "failed" until its retries run out, so the Failed count is
  what needs a person.
- Tests: `DownloadNoticeTrackerTest` (running vs waiting), `DownloadManagementTest` (retry all, clear
  all), `DownloadsSectionsTest` on the device (sections; opening at Failed). Checked on totum-api35:
  the intent opened Library → Downloads at Failed and at On the phone; two running downloads showed
  two notifications with their own bars under the summary.
