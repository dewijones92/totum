---
title: Google Drive backup through the system picker, merged on restore
kind: adr
status: accepted
updated: 2026-10-15
---

# 53. Google Drive backup through the system picker, merged on restore

- Status: Accepted
- Date: 2026-10-14 (decided), 2026-10-15 (built)

## Context

Dewi wanted his library backed up to his personal Google Drive automatically, everything except downloads and the
queue, with a prompt to set it up and a way to restore.

## Decision

- **Storage Access Framework, not the Drive API.** The user picks a Drive folder (or a single file) in Android's
  own picker and Totum keeps a persistable permission. No Google sign-in, no OAuth client, no Drive SDK. If Drive's
  provider will not let a folder be chosen, one file holds the latest and the daily copies stay on the phone.
- **One shared backup** for both phones. Restore **merges**: a restore that removed anything would be
  unforgivable with the wrong file.
- **Latest + 7 dailies**; **on every change**, settled 30 s, at most every 5 min while changes keep coming.
- **Extras: groups, pins, Recently** (and history). **Not** stats, Exsurge or the YouTube token.
- **Prompt at every launch** with Not now and Don't ask again; **offer a restore** on a fresh phone; hidden under
  tests.

## Consequences

- A backup can be up to 5 minutes stale while something keeps changing; a reorder within a playlist waits for the
  next change.
- Drive's folder support varies by provider version. The single-file mode is the fallback, and the card and the
  reports say which mode is in use.

## Amendment 2026-11-06: the side quests and the recorder are backed up too

The sweep found the promise ("everything except downloads and the queue") broken by features added after it. The backup's
settings map now also carries (each through `withExtra`, one wrapper for the shape the pins and Not-for-me wrappers share):

- `bedtime.v1`, the bedtime settings (restore replaces the phone's, like the other settings);
- `workapps.v1`, the work-app quiet groups, merged: groups missing on the phone are added, a group still at its factory
  default is replaced by the backed-up one, anything the person already changed is kept, and the switch is on if either
  side had it on;
- `recorder.v1`, the recorder's three choices;
- `dailyalarms.v1`, the alarm definitions, merged by id (the phone's wins, except an alarm still at its factory
  default; one-off alarms whose day has passed are skipped) and re-scheduled by the controller's own `update`.

Not backed up, because it is transient or belongs to the moment: a group's pause in progress, bedtime's "off for tonight",
each alarm's state for the day, the quiet groups' remembered states, the snoozed notification keys, "Show taps was switched
on by us", the recorder's last result. The backup scheduler is signalled by changes to bedtime, work-app and alarm
settings; a change to the recorder choices alone waits for the next change.
