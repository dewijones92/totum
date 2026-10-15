---
title: A group asks before it downloads
kind: adr
status: accepted
updated: 2026-10-15
---

# 45. A group asks before it downloads

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"whenever I add a group … I want an option of whether I want to download the items in the
group offline because I'm worried about downloading like a thousand item group offline to my phone"*. Queue
auto-download fetched the audio of everything queued; a Shuffle all of a channel queues hundreds of items. He
chose to be asked for every group, whatever its size.

## Decision

- `GroupDownloads` (`app/…/queue/`) holds a choice per queue group: ASK, YES or NO, saved in
  `files/group-downloads.txt`. A group that joins the queue starts as ASK; a group that leaves it is forgotten, so
  queuing it again asks again. On the first run with this build, every group already queued is YES, so nothing
  that was downloading stops.
- `QueueAutoDownloader` fetches a grouped item only when its group is YES (`groupAllows`), and re-passes when a
  choice changes. Items outside a group download as before.
- `GroupDownloadAsk`, in the shell, asks *Download "X" for offline? N items, audio only* (**Download** / **Not
  now**) while queue auto-download is on. A group's ⋮ has **Download this group offline** / **Stop downloading
  this group**.
- The offline banner and the report use the same rule (`GroupDownloads.fetchesAutomatically`); the report carries
  `downloads.groups` (each group's choice and size).

## Update, 2026-10-15: yes is held, no is one tap

Dewi: *"I want the yes … to be difficult to press and the no to be really obvious"*, for every download ask. The
group ask, a multi-select **Download** and an album page's **Download** all use `HoldToConfirmDialog`: a big
filled **Not now**, and **Download** as a `HoldToConfirmButton` that fills while held 1.5 s; a tap shows
"⏳ Hold to download" and does nothing. Tests: `HoldToConfirmButtonTest`, `HoldToConfirmDialogTest`,
`GroupDownloadAskTest` (device).

## Consequences

- A group answered NO stays streaming; its rows show as not fetched automatically.
- Tests: `GroupDownloadsTest`, `QueueAutoDownloaderTest`, `GroupDownloadAskTest` (device).
