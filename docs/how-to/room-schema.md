---
title: Room schema bumps, rollbacks and the invisible schema crash
kind: reference
updated: 2026-11-28
---

# Room schema changes

The rule itself is in `CLAUDE.md` ("Room schema changes"), which every session loads and which wins if the two ever disagree; change the rule there and here in the same commit. This file is the full account: the procedure, the numbers and the incidents that earned it, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28.

**A schema bump needs the whole `:core:database` instrumented suite, not just its migration test (2026-10-16):** `VideoIdMigrationTest` and `DownloadDatesTest` write a database at the current version and roll it back by hand, so a new column breaks them with "duplicate column name"; v28 went red in CI that way while `DownloadsMigrationTest` was green locally. Add the column to `RollBack.kt` (androidTest) when you bump.

**A schema bump blocks the baseline (2026-10-13):** Room has no downgrade path, so the previous build crashes on the newer database. Roll it back in place rather than wiping it: force-stop, pull `databases/totum.db*` with `run-as … cat`, `PRAGMA wal_checkpoint(TRUNCATE)`, drop the new columns, `PRAGMA user_version=<old>`, and set `room_master_table.identity_hash` to the old build's hash (`RoomOpenDelegate(<old>, "<hash>"` in its `build/generated/ksp`); push it back and delete `-wal`/`-shm`. The new build re-migrates it. Also: in `echo "$(date +%T) exit $?"` the `$?` is `date`'s, not the soak's.

### A schema change with no version bump makes the app un-openable — and it looks like nothing

Room **does** throw on an identity-hash mismatch (`IllegalStateException: Room cannot verify the
data integrity … Expected identity hash: X, found: Y`), and it throws when the DB is opened, which
on this app is during startup. Two things make it hard to read:

- **The crash can be invisible.** `com.dewijones92.uniapp` — the pre-rename app — is still installed
  on `totum-api35`, so when Totum died at launch the launcher fell through to THAT app, which looks
  almost identical and has its own empty database. I spent several minutes reading "No podcasts yet"
  and a stale schema as evidence about Totum. Check `adb shell pidof com.dewijones92.totum` and
  `dumpsys activity activities | grep topResumedActivity` before believing any screen.
- **Amending an already-applied migration is what causes it.** Editing the body of a migration whose
  version a dev device has already run (fine to do while the commit is unpushed) leaves that device
  on a schema whose hash no longer matches. The cheap fix keeps the sign-in: force-stop, then
  `run-as com.dewijones92.totum rm -f databases/totum.db*` — the DB goes, `shared_prefs/` and the
  YouTube token stay — and re-add whatever test data you need.
