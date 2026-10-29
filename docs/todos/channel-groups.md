---
title: Channel groups (groups of sources)
kind: todo
area: video
priority: high
status: shipped
updated: 2026-10-29
---

# Groups of sources

Dewi's ask (2026-07-29): *"special groups that are special channels… channel a
channel b channel c… I want the live stream, short, everything, so maybe like a
sub tab"*.

## Shape

Groups of **sources**, not of channels. A podcast feed and a video channel are
both `SourceId`s, so a group holds either or both and the merged feed merges what
it finds — the Unified law at no extra cost. See `SourceGroup` in `:core:domain`.

## Stages

1. **Domain + storage — shipped** (`4260907`). `SourceGroup` / `SourceGroupId`,
   the `SourceGroupStore` port, `RoomSourceGroupStore` and DB v15 (two additive
   tables). Membership is a toggle; the stored order is for editing, the feed
   sorts by date. Wired into `AppContainer`; instrumented tests cover the cascade
   and cross-group isolation.
2. **Merged feed fetch — shipped** (`ca0bd1e`). `GroupFeed` fans out over members
   concurrently and merges newest-first. Routes on the **sealed** `MediaSource` in
   one exhaustive `when` (`RoutedSourceItems`), never by sniffing a URL. Per-member
   fetch, not a filter over the account subscriptions feed, because that feed is a
   sample and carries neither shorts nor live reliably.
3. **Chips + picker — shipped** (`c24af57`). `FeedChoice` is a sealed
   account-feed-or-group; groups are chips beside YouTube's feeds, and a checklist
   dialog on any channel page creates and fills them.
5. **Rename and delete — shipped**. In the same picker, per row, behind an overflow;
   renaming happens in place rather than in a dialog on top of a dialog.
4. **Members carry their source — shipped** (`13fa325`). Stage 1 stored only ids;
   a group may name a channel you never subscribed to, so there was nothing to
   resolve it against and those members silently contributed nothing. v16.

## Verified on-device

Signed out, zero subscriptions, one unsubscribed channel grouped: "Tech" merged
to 20 items and rendered. That is the case that used to return nothing.

## Open

- How many members before the per-member fanout is too slow on entry? It is
  concurrent, but a twenty-channel group is still twenty requests. Consider
  refreshing in the background and showing what is cached.
- **Reordering is deliberately not built.** Member order within a group has no effect —
  the feed sorts by date, which is the point of the feature — and group order is the
  order they were created, which is fine for the handful of chips a person makes. If
  either ever matters it needs a `position` column and drag handles; it does not yet.

## The merge never merged (found 2026-10-29)

The status above said "shipped", and the chips and picker were, but the merged feed was not what it claimed: channel
pages give only an age, so every item was undated and "newest first" was each channel's block in turn. Found by
checking Dewi's Backpacker Ben / Carry On Cat / Bald and Bankrupt group against their RSS feeds; fixed by
[ADR 94](../adr/0094-a-groups-dates-come-from-the-feed-then-each-videos-own-day.md). The rest of the follow-up (new
marker, notifications, a Library tile, CRUD from the group page) is
[group-feeds-done-properly](group-feeds-done-properly.md).

