---
title: Backups are test-read weekly
kind: feature
status: built 2026-11-06 (tests to follow)
area: backup
updated: 2026-11-06
---

# Backups are test-read weekly

Dewi, 2026-11-06. Weekly (`backup-check`, a [periodic job](periodic-jobs.md)) `DriveBackup.checkLatest` lists the
location's copies, reads the newest (`copies()` order: latest and dailies by modified time) and decodes it with
`BackupCodec.decode`. It **never restores** (`service.restore` is not called).

Outcomes (`BackupCheck`): `Readable(copy, where, bytes)`, `Unreadable(why, where)` (no copy found, could not be opened,
read back empty, not a backup, or from a newer Totum), `NotSetUp` (the job is skipped and records nothing).

## What the person sees

- **Settings → Backup:** under the status, "✅ Backup checked 2 days ago", or "❌ Backup could not be read: <why>"
  with "(last good check 3 days ago)" when there was one. Hidden until a check has run, and when Drive backup is off.
- **A failure notifies** ("❌ Backup could not be read", housekeeping channel).

## Diagnostics

`backup` lines: `dewidebug backup check (weekly): <copy> from <where> reads fine [bytes, modified, subscriptions]`, or a
warning with the reason; the `jobs` lines.

## Tests still to write (written in part 2026-11-14: see [the tests index](../tests/_index.md))

`checkLatest` against an in-memory `BackupFolder` (good copy, corrupt copy, newer version, empty, no copies, read
throwing); the row text for each `LastRun`; a device check against a real Drive location (not done).
