---
title: Backups are test-read weekly
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Backups are test-read weekly

Dewi 2026-11-06. Weekly, `DriveBackup` lists, reads and decodes the latest copy WITHOUT restoring
(`BackupCodec.decode`), and the row says "✅ Backup checked 2 days ago" (persisted).
