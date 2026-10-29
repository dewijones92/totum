---
title: Groups say what's new, notify each new video, and live in the Library
kind: adr
status: accepted
updated: 2026-10-29
---

# 95. Groups say what's new, notify each new video, and live in the Library

- Status: Accepted
- Date: 2026-10-29

## Context

Dewi, 2026-10-29, about his Backpacker Ben / Carry On Cat / Bald and Bankrupt group: *"make it clear once I go into
the app … there's new ones there, and I want to have notifications if there's new ones … Obviously, I want to be able
to CRUD these things."* Groups were a chip on the Videos tab only (behind Show videos), with no notion of new, and
managed only from a dialog on a channel page. His answers: a **Library tile** per group plus the chip; **"N new" and
🆕 on items** with a "New since you last looked" divider; **one notification per new video**, checked about hourly on
any network; Shorts and live included (ADR 94's feed brings them).

## Decision

- **`GroupNews`** keeps, per group, when it was last opened (`seenAt`), which videos are new since (`fresh`) and which
  have been notified, in `files/group_news.json`. A group's first check only records the time, so old videos are never
  announced. Later checks read each member's newest items through the same `LatestUploads.of` as New uploads (RSS, both
  pillars), count what was published after `seenAt`, and notify each new video once (`GroupNewsNotifier`, channel
  "New in your groups": "🆕 <channel>", the title, the group; tapping opens the video through the app's shared-link
  path). A notification that could not be posted is tried again next check.
- **When:** `GroupNewsWorker` every 60 minutes on any network, and 20 s after the app opens (not under instrumentation).
- **Opening** a group (its Library page, or its chip on the Videos tab) clears its count and hands back the previous
  `seenAt`; the page puts everything newer above "🆕 New since you last looked (N)", each marked 🆕 New, then "Earlier".
- **The Library opens with its Groups** (Dewi: *"I'd love it if groups were at the top … the groups are very important
  to me"*), above Pinned and Recently, and has a Groups section: a tile per group ("🆕 3 new", highlighted, or "📺 3 channels") and New group.
  The group page (`GroupScreen`) loads through the Operations strip, refreshes (🔄), and edits (✏️ `GroupEditSheet`):
  rename, channels ticked on and off (members first, then a search of your subscriptions), and delete with a
  confirmation. The channel page's group dialog still adds any channel, subscribed or not.
- The Videos chip reads "Travel · 🆕 3" while there are new videos.
- **The pulse** (Dewi: *"when we open our app … a visual sort of pulse thing, if something in our group is new"*): the
  saved counts are read as the app starts (`GroupNews.warm`), so at once the Library button in the bottom bar carries a
  red "🆕 N" badge on a highlighted pill and pulses, and a group tile with new videos pulses too (`Modifier.pulsing`):
  four beats when the count appears or changes, then still (ADR 61: nothing animates for ever).

## Consequences

- One RSS request per member per hour (~6 KB each), through the polite gate.
- Reports carry `groups.news.<id>` (fresh, notified, seenAt); every check, new video, notification and opening is
  logged under `[group]`.
- Verified on totum-api35 with the real channels: create, add three channels by search, rename; with `seenAt` set to
  20 Oct the check found 3 new and posted 3 notifications, the tile showed "🆕 3 new", the page showed the divider and
  markers, and tapping a notification played that video.
- Tests: `GroupNewsTest` (first look, new once and counted, opening clears, never opened, a failed post retried, the
  saved count known at start-up).
