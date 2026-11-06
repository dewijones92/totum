---
title: Stored links no longer carry the old shared token
kind: todo
status: built 2026-11-06 — stripped on read and write in the mapper and in the backup codec; not yet on a phone
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Stored links no longer carry the old shared token

Found 2026-11-06 in Dewi's Full backup: 31 queue rows still held the old shared home-server token in their links
(`totumToken=…`, 62 occurrences), so it travelled into backups. The token itself was retired on the Pi the same day
(`server.py retire-legacy`; checked: header and link both 401; phones unaffected, they send device tokens). Fix: a
one-off migration strips the parameter from stored queue, history and playlist links, and backups never write it.

Whole-batch review: no SQL migration (a schema bump needs the whole :core:database suite); strip the parameter in `PlaylistItemColumns` when rows are read and written, and in `BackupCodec`.

## Built, 2026-11-06

One helper, `LegacyLinkToken.strip` in `:lib:common`, removes the `totumToken` parameter from a link (or from a whole
text holding links) and keeps the rest of the URL valid. `playlistItemFrom` strips `handle` and `mediaUrl` when a row
is read, `typeAndHandle()` and `HttpUrl.storedValue()` strip them when a row is written (queue, history, playlists and
downloads share them), and `BackupCodec` strips the whole file on `encode` and on `decode`, so restoring an old backup
cleans it. No SQL migration and no schema bump: a stored row is cleaned the next time its table is written. A strip is
counted and logged at 1, 2, 4, 8 … links per launch (`link` tag), never per row.
