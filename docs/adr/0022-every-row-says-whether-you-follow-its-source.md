---
title: Every row says whether you follow its channel or show, and the mark subscribes
kind: adr
status: accepted
updated: 2026-10-08
---

# 22. Every row says whether you follow its channel or show, and the mark subscribes

- Status: Accepted
- Date: 2026-10-08

## Context

Dewi, 2026-10-08: *"i want it obvious in the app when scrolling through items to see whether or not I am
subscribed to the channel"*. He chose, from options put to him:

- mark **both ways**: "✅ subscribed" and "➕ not subscribed", in words as well as the emoji;
- on **every row**, wherever one appears (feeds, search, queue, history, playlists, Library, related);
- **podcasts too**, the same mark for both pillars;
- the mark is **tappable**: ➕ subscribes at once, ✅ asks "Unsubscribe from X?" first;
- when the app cannot tell, a **question mark** ("❔ unknown");
- on the Home feed, whose tiles name the channel only by its display name (a live `FEwhat_to_watch`
  response on 2026-10-08 carried a `UC…` id on 0 of 16 tiles, against 45 of 45 on Subscriptions; the
  long-press menu that holds it is fetched separately), **match by name**: ✅ when the name matches a
  subscribed channel, otherwise ➕, and a ➕ tap looks the channel up before subscribing.

## Decision

- One rule in `:core:domain` (`Following.kt`): `FollowedSources.of(item, pillar)` returns
  `Subscribed(source)`, `NotSubscribed(source)` or `Unknown(because)`. A video is judged by the channel its
  listing named (`MediaItem.sourceUrl`), matched on the `UC…` id; an episode by its feed (`sourceId`).
- A video whose listing names no channel is judged by its `author` against the subscribed channels'
  titles (trimmed, case-insensitive): `Subscribed(byName = true)` or `NotSubscribedByName(item)`. A
  channel id, when there is one, always outranks a name. A ➕ tap on a name-only row runs
  `SourceLocator.locate` (the same lookup as Go to channel, a yt-dlp extraction of a few seconds, which
  reports the canonical `/channel/UC…` URL) with a "Looking up …" toast, then subscribes.
- Unknown, rather than a guess, when: the row names no channel or feed; YouTube is signed out; the
  account's list has not loaded yet (`AccountSubscriptions.loaded`), so a launch never flashes
  "not subscribed" on every row; or the channel is named by handle only and is not in the list by that
  URL or by name, since a handle cannot be compared with the account's `UC…` ids.
- `statedChannel()` / `statedFeed()` moved from `DefaultSourceLocator` into the domain, so "which source
  does this row name?" has one answer for go-to-source and for the mark.
- `ProvideFollowing` (inside `ProvidePlayStates`) gives every `MediaItemRow` the mark and the action by
  default, like play and download state. A row with nothing provided (previews, tests) draws no mark.
- `SourceFollowing` subscribes either kind of source: a channel through the account
  (`AccountSubscriptions.setSubscribed`, the same write as the channel page), a feed through
  `PodcastRepository`. A refusal shows a toast.
- The mark sits on the channel / show line, beside the name, and wraps with it.

## Consequences

- A video from a channel you follow on another device shows ✅ once the account list has loaded.
- A name match can be wrong: two channels sharing a name, a renamed channel, or a collaboration credited
  as "X and 2 more" (shown ➕ even when X is followed). Accepted for the Home feed's sake.
- Rows with no channel and no author (some shared links before they are looked up) show "❔ unknown";
  tapping it does nothing.
- Unsubscribing from a podcast from a row removes its stored episodes, as it does everywhere else.
- `dewidebug [follow]` lines: the basis whenever it changes (`signedIn`, `youtubeListLoaded`, channel and
  feed counts), the first row shown unknown for each reason and the first judged by name (with the row's
  `author`, `sourceUrl` and `sourceId`), every lookup by name with its time and result, every subscribe /
  unsubscribe from a row and its result, and a "kept" at the question.
- Covered by `FollowingTest` (19), `SourceFollowingTest` (10), `SubscriptionsFetchedOnceTest` (loaded flag)
  and `FollowMarkTest` (instrumented).
