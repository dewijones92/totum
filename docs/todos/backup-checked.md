---
title: Backups are test-read weekly
kind: todo
status: built; tests written in part
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# Backups are test-read weekly

Dewi 2026-11-06. Weekly, `DriveBackup` lists, reads and decodes the latest copy WITHOUT restoring
(`BackupCodec.decode`), and the row says "✅ Backup checked 2 days ago" (persisted).

Built: see [../features/backup-checked.md](../features/backup-checked.md).

## Tests written 2026-11-14

`DriveBackupCheckTest` (a good copy, only the newest read, corrupt, newer version, empty, no copies, unopenable, unlistable, not set up, and that it never restores). Still owed: the row text for each `LastRun`.
