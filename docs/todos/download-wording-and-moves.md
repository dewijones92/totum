---
title: Every download says what, from where, to where; Move to Pi moves the file
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Every download says what, from where, to where; Move to Pi moves the file

Dewi 2026-11-01 and 2026-11-05: "Downloading at home" and "Delete" are ambiguous; say what is downloading and from
where to where (web → phone, web → Pi, Pi → phone, phone → Pi). One formatter (state in core/domain, words in
ui/common) replaces `MediaItemStatus.downloadingText`, `HomeStateText`, `TorrentHitRow.torrentHomeWords`,
`DownloadNotifier`, `RunningDownloadNotifications`, `ops_home_download`; every delete says where from.

Report 20261102T132125: "Move to Pi" asks the Pi to re-download from the web and waited two 20 s polls. Decided: a real
upload (`PUT /files?id=`, streamed, size-capped; the Pi's bodies are read unbounded today), polling every 2 s while a
job is busy, a real percentage.
