---
title: Share and copy link are on every item menu, and a group shares its titles
kind: adr
status: accepted
updated: 2026-11-06
---

# 134. Share and copy link are on every item menu, and a group shares its titles

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06 (batch A): one item-menu action to send something to someone, for every kind of item. The item menu
(`ActionSheet`) already had a "Details" section whose expanded view could copy a link, but nothing used Android's
share sheet and no menu offered the link directly. A queue group, a torrent pack and a playlist have no address.

## Decision

- **One rule says what an item shares** (`MediaItem.shareLink` in `:core:domain`, answering `ShareLink.Link` or
  `ShareLink.TitleOnly` with the reason, which is logged):
  - a YouTube item, which covers videos, Shorts, live streams and songs, shares its canonical watch address
    (`canonicalWatchUrl`), found from its media address or, failing that, from its id, as `radioVideoId` finds it;
  - a torrent shares its title only: its address is the Pi's stream, and no link carries the home-server token
    (ADR 90);
  - any other item (a podcast episode, an audiobook chapter) shares its own media address, except an address on the
    Pi's host (`HomeServerAuth.homeHost()`), which shares the title only for the same reason;
  - with no address known, the title.
- **Two actions on `ItemActions`, so every list and every way in has them:** `share` (always offered: the title and
  the link when there is one, through Android's share sheet) and `copyLink` (offered only when there is a link,
  through the same `rememberCopier` the details section uses). They are in `ActionSheet`, which every item menu
  already goes through (rows, the full player, the Shorts reel, Recently and Pinned songs, a single torrent file).
- **A group has no address, so it shares its list of titles** (`ItemActions.shareList`): the group's title, then
  numbered titles, capped at 100 with "➕ and N more". It is offered in a queue group's menu and in the menu a torrent
  pack opens. This is the recorded ADR 108 exception: a group is not an item, so it does not get "Copy link".
- Copying and sharing run on the shell's scope (`rememberCopier`, `rememberSharer`, built once in
  `ProvideItemActions`), not the sheet's, because the sheet leaves the composition the moment an action is tapped.

## Consequences

- Sharing a podcast episode sends its enclosure address, which is the only public address the app holds; the
  episode's own web page is not known.
- Not shared yet, on purpose: a multi-selection's bulk bar, the Library's group tiles and pin cards that are not items
  (albums, artists, playlists). They would need their own addresses (music.youtube.com pages) and are a follow-up.
- Every share logs its inputs and the reason for its answer under `share` with `dewidebug`.
