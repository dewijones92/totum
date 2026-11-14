---
title: Every download says what, from where, to where; Move to Pi moves the file
kind: todo
status: built; tests written in part
batch: A (2026-11)
priority: high
updated: 2026-11-14
---

# Every download says what, from where, to where; Move to Pi moves the file

Dewi 2026-11-01 and 2026-11-05: "Downloading at home" and "Delete" are ambiguous; say what is downloading and from
where to where (web → phone, web → Pi, Pi → phone, phone → Pi). One formatter (state in core/domain, words in
ui/common) replaces `MediaItemStatus.downloadingText`, `HomeStateText`, `TorrentHitRow.torrentHomeWords`,
`DownloadNotifier`, `RunningDownloadNotifications`, `ops_home_download`; every delete says where from.

Report 20261102T132125: "Move to Pi" asks the Pi to re-download from the web and waited two 20 s polls. Decided: a real
upload (`PUT /files?id=`, streamed, size-capped; the Pi's bodies are read unbounded today), polling every 2 s while a
job is busy, a real percentage.

## Built (2026-11-06)

ADR 126. One `Transfer` state in `:core:domain` and one `transferText` mapper; every delete names where from; `HomePiCall`
is the one Pi call shell; `PUT /files` streams the phone's file to the Pi (capped, temp file then rename, registered as a
finished download); the poll is 2 s while a job is busy and the strip observes the known state. Owed: the Pi's nginx
`/fetch/` body limit, a deploy of the CLI, unit tests (below), and one on-phone move.

Tests the testing stretch must add *(written in part 2026-11-14: see "Tests written 2026-11-14" below for what is done and what is still owed)*: `transferText` for every phase and place pair; `HomeJob.transfer()` and
`percentOf`; `HomePiCall` (blank token, HTTP error with and without the Pi's `error`, unreadable answer, IO error);
`HomeRoutes` PUT `/files` (stored, 411 without a length, 413 over the limit, 400 on a wrong id or a short body, 409
busy); `DefaultDownloadManager.adopt` (stored, busy, sound-only over full, replaced older copy, stale `.incoming.part`
cleared); `HomeUploadFrame`/`HomeUploadBody` round trip and cancel; `PhoneUploads` (progress to strip and notice, move
deletes only after the job shows, a refusal or cancel keeps the phone copy, over-limit refused locally);
`HomeDownloads.sendFromPhone` upload path (the existing tests place a non-existent file, so they exercise the
fallback only); the strip observing `known` (percent steps, done, failed, job vanishing).

Consistency sweep 2026-11-06: the progress strip (group titles and row titles), the Pi notices and the done/refused
lines now read through `transferRoute`/`transferTitled` ("⬇️ Web → 🏠 Pi · title", a middle dot, never a colon); the
leftover "at home" wording in `SendHome` and `HomeFollower` says "the Pi"; a bulk delete says "🗑️ Delete from both"
(and its confirm says the Pi's copies go too) when any selected item has a Pi copy, and the row's downloaded tick says
so in its description. Not touched: the "Send home" share-sheet label and the "home server" setting names, which name
the feature and the server rather than where a file is.

## Tests written 2026-11-14

`TransferTest` (`:core:domain`: `Transfer`, `percentOf`, `transfer(fromPi)`), instrumented `TransferTextTest` (every route and phase in words). Still owed: `HomeRoutes` PUT `/files`, `adopt`, `HomeUploadFrame`, `PhoneUploads`, `sendFromPhone`.
