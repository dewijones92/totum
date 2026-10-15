---
title: Google Drive backup
kind: feature
status: built 2026-10-15 — verified on the emulator against a local folder; not yet seen with Google Drive on the phone
area: backup
updated: 2026-10-15
---

# Google Drive backup

Dewi, 2026-10-14: *"a backup to Google Drive option that backs up everything except files and my queue … prompt
me … be able to restore from it as well."* Decisions in [ADR 53](../adr/0053-google-drive-backup.md).

- **What:** the existing backup (`BackupService`): podcast subscriptions, playlists, positions and played marks,
  settings, pins and Recently (both carried in the settings map, `pins.v1` and `recent.v1`), plus groups and play
  history, new in this change. Not downloads, not the queue (`create(includeQueue = false)`), not stats, Exsurge or
  the YouTube token. YouTube subscriptions live in the account.
- **Where:** a location chosen through Android's picker. A **folder** holds `totum-backup-latest.json` plus one
  `totum-backup-YYYY-MM-DD.json` a day, the newest 7 kept. **One file** (for when Drive will not let a folder be
  chosen) holds the latest, and the 7 daily copies are kept on the phone. Both phones can share one location.
- **When:** `BackupScheduler` backs up 30 s after changes settle, and at most 5 min after the first unsaved change
  while things keep changing (playback position). Signals: subscriptions, playlists (a playlist edit that leaves
  its item count unchanged, such as a reorder, waits for the next change), positions, groups, history, pins,
  Recently and settings. It runs on IO.
- **Restore** merges: nothing on the phone is removed, groups gain missing members, history gains missing items,
  and pins and Recently gain missing entries.
- **Prompt** at every launch until set up or "Don't ask again" (hidden under instrumentation). On a phone with no
  history and no playlists it offers a **restore** instead: choose the folder (or the file) the other phone backs
  up to; Totum restores the latest and keeps backing up there.
- **Settings → Backup:** status (when it last ran and where, or why it failed), Back up now, Restore… (lists the
  copies), Turn off.
- **Reports:** `backup.location`, `backup.status`, `backup.neverAsk`; every backup and restore is logged under
  `backup`.

Verified on `totum-api35` with a local folder through the same picker: the launch prompt appears, the first backup
writes the latest and daily files (45 KB), pausing playback triggers a backup 32 s later ("history, progress"),
Back up now updates the card, and Restore… lists both copies and merges. **Not verified:** Google Drive itself (not
on the emulator), and the fresh-phone restore prompt (testing it would wipe the emulator's YouTube sign-in).

Tests: `DriveBackupTest`, `BackupSchedulerTest`, `BackupServiceTest` (groups and history, queue left out).
