---
title: Consistent everywhere, with its exceptions recorded
kind: adr
status: accepted
updated: 2026-11-01
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

Fixed on 2026-11-01: queue rows with no date (report 0.1.814). The signed-in TV answer only asks the account's watch
page, where the date is, when the title is unknown, so a listing that had a title but no date never learned one. The
metadata repair (`SharedMetadataRepair`) now also fills a missing date from the watch page for the queue rows around
what is playing (5 before, 30 after) and every download, at most twice per video a session; songs are left alone. An
item entering the queue with no date is logged with its source (`[facts]`), so the next report names the path.
The 🏠 At home rows became the shared item row (poster, menu, play), with 📥 Copy to the phone now, and say "stays
there until you copy it" when they will not copy by themselves.

Also fixed on 2026-11-01 (the overnight consistency batch):

- **Torrents are shown as torrents**, not podcasts: one rule (`isTorrent` in `:core:domain`, `Shown` in the app)
  decides the maker line (🧲 and the torrent's name), the row's status icon, the player's badge and the mini player's
  glyph. A torrent still plays through the podcast handle; only what is shown changed.
- **Queuing an entry again keeps what its row knew**: its sound-only route (a torrent's audio playlist) and its
  learned facts, not only for the playing entry.
- **One clock parser** (`ClockText` in `:lib:common`) for YouTube durations, RSS durations and chapter times.
- **D4, recent searches are chips everywhere** (`RecentChips`): Search's list became the same chips as every filter,
  channel, transcript and ✨ Ask box; a tap searches, ✕ or a long press forgets; Search keeps its heading and Clear all.
- **An episode with neither guid nor enclosure is identified by its title and date**, not by its position, which
  moved whenever the show published.

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
- D6 a sort sheet on every list of items (history, notifications, playlists, music pages, group pages).
- The audit's remaining items: stored queue/history rows lose show notes and chapters (a schema bump); private page
  spinners into the strip; six empty-state looks; three search-field designs.

## Consequences

- New work is reviewed against this list; an exception added later is recorded here.
