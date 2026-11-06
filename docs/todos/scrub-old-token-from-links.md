---
title: Stored links no longer carry the old shared token
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Stored links no longer carry the old shared token

Found 2026-11-06 in Dewi's Full backup: 31 queue rows still held the old shared home-server token in their links
(`totumToken=…`, 62 occurrences), so it travelled into backups. The token itself was retired on the Pi the same day
(`server.py retire-legacy`; checked: header and link both 401; phones unaffected, they send device tokens). Fix: a
one-off migration strips the parameter from stored queue, history and playlist links, and backups never write it.
