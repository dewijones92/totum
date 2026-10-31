---
title: Consistent everywhere, with its exceptions recorded
kind: adr
status: accepted
updated: 2026-10-31
---

# 108. Consistent everywhere, with its exceptions recorded

- Status: Accepted
- Date: 2026-10-31

## Context

A video opened from a notification showed no date while the same video had one in every list. Dewi: *"that date
issue just now is indicative of multiple things not being consistent throughout the app … I want this app to be as
consistent as possible … in the special cases, we'll discuss."* Four read-only scouts audited item facts, actions per
kind of item, UI and wording, and data paths on `4edbb3b1`; about 60 findings
(`C:\Users\DewiJones\claude-html\totum-consistency-audit-2026-10-31.html`). Dewi accepted the recommended answer to
each special case and asked for decisions to be recorded.

## Decision

The rule (also in `CLAUDE.md`): the same kind of thing looks, behaves and carries the same facts wherever it appears;
a change to one path checks its siblings; a deliberate exception is never built silently, it is asked and recorded.

Fixed on 2026-10-31 (batches 1–4): dates by link and notification, one day-only rule (noon UTC), one date format,
LIVE by link, chapters into queue rows, failed lookups retried, channel fallback facts, radio pin art, song rows,
backups keep dates and views, search kinds, posters fitted, download cards, one watch-URL builder; every download
through one chooser with an honest label, YouTube-only choice, pinned and paused films with pictures, mode toasts,
follow marks, one nearly-finished rule; the wording vocabulary (ADR 43); one list loading look (the skeleton) and
one list message; an emoji'd queue empty state; a selection's download uses the remembered choice.

Recorded exceptions (kept on purpose):

- **Search results have no sort sheet**: they are ranked by relevance.
- **The Shorts reel and the "new content" notification show the title only**: a full-screen reel and a
  notification line are not rows.
- **The cast strip keeps its own spinner**: it is the TV's live status, not a task with an end.
- **The queue's sort sheet ticks "As listed" after a sort**: a queue sort reorders the queue (ADR 66).
- **`canonicalItemId` only rewrites exact watch URLs**: it mirrors the v26 database migration, so widening it would
  stop restored ids matching stored rows.
- **Group and new-upload items have no duration or views**: the channel RSS feed carries neither.
- **Row tags are upper-case badges, facts are words**; "Show more", "Show all N" and "⬇️ Show N more" are different
  affordances (ADR 43).

Decided, not yet built (next consistency batch):

- D1 torrent results get the full long-press menu (prepared on the Pi when long-pressed).
- D2 intro/outro skips only for a season pack, keyed by the show's title; never a single film.
- D3 the thin top busy bar folds into the progress strip; needs a design for background lookups, which the bar
  covers today and which must not fill the strip.
- D4 recent searches as chips everywhere (Search's list becomes chips, long-press removes one).
- D6 a sort sheet on every list of items (history, notifications, playlists, music pages, group pages).
- The audit's remaining items: torrent films labelled 🎙️ Podcast; re-queuing a torrent file drops its sound-only
  stream; stored queue/history rows lose show notes and chapters (a schema bump); private page spinners into the
  strip; six empty-state looks; three search-field designs; two clock-duration parsers; a podcast episode with no
  guid is identified by its position.

## Consequences

- New work is reviewed against this list; an exception added later is recorded here.
