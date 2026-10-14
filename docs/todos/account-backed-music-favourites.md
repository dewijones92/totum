---
title: Music favourites saved to the YouTube Music account
kind: todo
status: built 2026-10-14 — see features/youtube-music.md and ADR 32
area: music/account
priority: medium
requested: 2026-10-12
updated: 2026-10-14
---

# Music favourites saved to the YouTube Music account

Dewi asked for an equivalent to starring a song or artist, reflected server-side. He confirmed
that this means his YouTube Music account, with favourites easy to browse again in Totum, and
requested a backlog item. **Built 2026-10-14** ([ADR 32](../adr/0032-music-favourites-live-on-the-account.md)):
Like in a song's menu, Save on album pages, Follow on artist pages, "Your favourites" on the Music home.
The three decisions below were answered: YouTube Music's own labels, a section near the top of the Music
home, and no offline queue (a write needs the network and says so when it fails).

## Requested behaviour

| Item | Account action | Totum behaviour |
|---|---|---|
| Song | Like / unlike | Show the current account rating and make liking accessible where songs are browsed and played |
| Artist | Subscribe / unsubscribe | Offer Follow on the artist page and show the account's subscription state |
| Album | Save to / remove from library | Offer Save on the album page and show whether it is in the account library |

Provide an easy way to browse liked songs, followed artists and saved albums in Totum. Existing
favourites in the account should appear, and changes made in YouTube Music should be reflected
when Totum refreshes them.

These actions use YouTube's existing account meanings. A song's like and its library membership
are separate: unliking must not imply that YouTube automatically removes it from the library.
Pins remain shortcuts; an account favourite must be identifiable separately from a pin.

## Existing pieces to reuse

- The full player's song Like is already wired through `WatchViewModel` and `YouTubeActions`.
- `YouTubeActions.setSubscribed` already writes channel subscriptions. Music-row follow marks
  currently refer to the uploading channel; the artist action must use the intended artist id.
- `YouTubeMusicLibrary` reads account albums, playlists and artists. Dedicated library-save
  writes and a liked-songs browsing surface still need work.
- Artist and album pages currently offer local pins, but no dedicated account Follow / Save.

Reuse the shared rating and subscription seams used by videos and channels. Keep account state
authoritative, with any local cache serving display and offline access rather than inventing a
second favourites collection. See [YouTube Music](../features/youtube-music.md).

## Decisions for refinement

- Whether to present one consistent star affordance or retain Like / Follow / Save labels.
- Where the favourites browsing surface belongs within Music and Library.
- How pending changes behave offline, including retries and visible failure states.

## Done when

- Song, artist and album actions can be changed and their actual account state read back.
- The resulting state is visible in YouTube Music as well as after restarting Totum.
- Existing account favourites and changes made elsewhere are shown after refresh.
- A failed write does not leave Totum claiming that the account was changed successfully.
- Regression tests cover reading, writing, removing and failures through the shared seams;
  device checks verify the user controls and account readback.
- Diagnostics identify the item, intended action, server outcome and refreshed state, so a
  phone report can establish whether the action reached the account.
