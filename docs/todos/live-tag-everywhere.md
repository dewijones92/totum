---
title: 🔴 LIVE everywhere, kept up to date
kind: todo
status: decided
batch: A (2026-11), phase 4
priority: medium
updated: 2026-11-06
---

# 🔴 LIVE everywhere, kept up to date

Dewi 2026-11-06: *"tag live ones like [members-only] … I want consistency"* (CLAUDE.md "Consistent everywhere",
ADR 108). The 🔴 LIVE tag exists (`tag_live`, `MediaItemRow.kt` ~338 for `MediaContentKind.LIVE`, beside 💎 MEMBERS)
but appears only where the source says the item is live (e.g. `FeedVideoMapping` for the account feed); RSS-based
group lists, items queued before going live and finished streams are wrong or untagged.

Decided: every list a live item can appear in (search, channel pages, group pages, the queue, the subscriptions feed,
notifications, the player, Ask cards) shows the same tag; Totum learns "live" whenever it looks a video up (resolve,
the watch page, the history poll) and stores it on the item through the existing fact-learning path
(`learnFacts`/`adoptFacts`, the same as dates and artists), so every row of that item updates; an upcoming one shows
🔴 STARTS SOON (built today); a stream that has ended becomes an ordinary video tagged "⏺️ Was live" and plays
normally (speed and silence cutting allowed again); the sort-and-filter sheet (ADR 66, `ItemFilters`) gets a
"🔴 Live now" filter. Log every kind change (was, now, why).
