---
title: Google Drive backup
kind: feature
status: built 2026-10-15 — verified on the emulator against a local folder; not yet seen with Google Drive on the phone
area: backup
updated: 2026-11-07
---

# Google Drive backup

Dewi, 2026-10-14: *"a backup to Google Drive option that backs up everything except files and my queue … prompt
me … be able to restore from it as well."* Decisions in [ADR 53](../adr/0053-google-drive-backup.md).

- **What:** the existing backup (`BackupService`): podcast subscriptions, playlists, positions and played marks,
  settings, pins and Recently (both carried in the settings map, `pins.v1` and `recent.v1`), plus groups and play
  history, new in this change. Not downloads, not the queue (`create(includeQueue = false)`), not stats, Exsurge or
  the YouTube token. YouTube subscriptions live in the account.
- **Also (2026-11-06):** the bedtime settings, the work-app quiet groups, the recorder's choices and the daily alarms
  (definitions only), as keys of the settings map (`bedtime.v1`, `workapps.v1`, `recorder.v1`, `dailyalarms.v1`). Restore
  puts bedtime and recorder choices back as saved; work-app groups and alarms are merged (see
  [ADR 53](../adr/0053-google-drive-backup.md)) and the alarms re-scheduled.
- **Not in a backup (transient):** a work-app group's pause in progress, bedtime's "off for tonight", an alarm's state
  for today, the recorded "Show taps was switched on by us" value and the snoozed notification keys.
- **Not in a backup, by decision (2026-11-07, my call, found by the end-of-batch review):** trip mode's settings
  (`totum_trip_mode`: an armed departure moment and its per-group choice) and the colour each home target was given
  (`totum_target_colours`). A trip's departure is a single moment, so a restore onto another phone would arm a trip it
  is not on; a target's colour is only the one this phone picked on first sight of the target (the targets themselves
  come from the Pi), so losing it just picks again. Putting either in needs a `withX` settings wrapper beside the
  others and a change flow in `Extras.others`, both call-site only; it was not done because neither has a reason a
  restore should bring it back.
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
- **Weekly check:** the newest copy is read and decoded without restoring it, and the row says "✅ Backup checked 2 days ago"
  or "❌ Backup could not be read: …" (a failure notifies); see [backup-checked](backup-checked.md).
- **Reports:** `backup.location`, `backup.status`, `backup.neverAsk`; every backup and restore is logged under
  `backup`.

Verified on `totum-api35` with a local folder through the same picker: the launch prompt appears, the first backup
writes the latest and daily files (45 KB), pausing playback triggers a backup 32 s later ("history, progress"),
Back up now updates the card, and Restore… lists both copies and merges. **Not verified:** Google Drive itself (not
on the emulator), and the fresh-phone restore prompt (testing it would wipe the emulator's YouTube sign-in).

Tests: `DriveBackupTest`, `BackupSchedulerTest`, `BackupServiceTest` (groups and history, queue left out).

Tests owed (2026-11-06): `withExtra` (a key is exported, restored, and a malformed value is logged not thrown);
`mergedWorkApps` (adds a missing group, replaces a factory-default one, keeps a changed one and its pause, switch is
OR) and `mergedAlarms` (adds missing, skips a past one-off, replaces a factory pickup or fridge, keeps the phone's);
`encodeChoices` and `decodeChoices` round trip; a restore through `DailyAlarmController` re-schedules.

## Links carry no token

`BackupCodec` strips the retired shared home-server `totumToken` parameter from every link when it writes a backup and
when it reads one, so an old backup restores clean and a new one never carries it (found in a real Full backup,
2026-11-06; todo `scrub-old-token-from-links`).
