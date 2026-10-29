---
title: Group feeds done properly — date order, new marker, notifications, a Library tile
kind: todo
area: video
priority: high
status: decided 2026-10-29, in progress
updated: 2026-10-29
---

# Group feeds done properly

**Ask (Dewi, 2026-10-29):** *"Backpacker Ben and the related channel Carry On Cat and related channel Bold and
Bancroft [Bald and Bankrupt]. I want a way to see those all in just one list … sorted by date … make it clear once I
go into the app … there's new ones … notifications if there's new ones … make sure the date ordering works correctly
… scroll down to … 40 items … cross-check with browser tests"*; then *"I know we did something in July … check how
good it is."*

**What the July groups actually did (checked 2026-10-29 on totum-api35, the real `groupFeed.itemsFor` against the
three channels' RSS):** all 60 items had `publishedAt = null`, so the newest-first sort did nothing and the list was
Backpacker Ben's 20, then Carry On Cat's 20, then Bald and Bankrupt's 20 (Carry On Cat's newest, truly #1, sat at
#21). Each member gives one page (20) and no more. No new marker, no notifications.

**His answers (AskUserQuestion, 2026-10-29):**
- **Where:** a Library tile per group ("🎒 Travel · 3 new") opening the list directly (not behind Show videos), and the
  Videos chip stays.
- **New marker:** "N new" on the tile and chip; 🆕 on every item newer than the last visit, with a "New since you last
  looked" divider; opening the list clears the count.
- **Notifications:** one per new video, tapping opens it; checked about hourly on any network, through the polite gate.
- **Shorts and live:** everything, in date order.

**Ground truth for the check:** each channel's RSS (`/feeds/videos.xml?channel_id=`) gives exact publish times for its
newest 15 (Shorts and live included); merged, those are exact down to the point where the busiest channel's 15th
falls off (2026-06-14, item 31, on 2026-10-29).
