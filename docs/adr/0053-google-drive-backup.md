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
