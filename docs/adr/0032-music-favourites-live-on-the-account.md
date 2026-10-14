---
title: Music favourites live on the YouTube Music account, and a change counts only once the account shows it
kind: adr
status: accepted
updated: 2026-10-14
---

# 32. Music favourites live on the YouTube Music account, and a change counts only once the account shows it

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi wanted to like songs, follow artists and save albums from Totum, with the result on his YouTube
Music account and easy to browse again. Asked on 2026-10-14, he chose YouTube Music's own words (Like,
Follow, Save) and a "Your favourites" section near the top of the Music home. Only the signed-in TV
client accepts the app's token, so every write and read goes through it. Probed live on 2026-10-14 (each
write undone straight after):

- Saving an album is `like/like` on its `OLAK5uy_` playlist; removing it is `like/removelike`. The album
  appears in, and leaves, `FEmusic_liked_albums`.
- Following an artist is a subscription to the artist's channel (`subscription/subscribe`); it shows in
  `FEchannels` and at the top of `FEmusic_library_corpus_artists`, which is therefore "followed artists"
  (name only, no channel id).
- Liked songs are the `VLLM` playlist; its header says how many (1,206 on Dewi's account), too many to
  page through just to mark rows.
- **The account lags a write.** The first live save in the app read the library straight back, did not
  find the album, and reported "not confirmed" while the account had it.

## Decision

- One seam, `MusicFavourites` (app `music/`): the shelf (liked-song count, saved albums, followed
  artists), and the three writes. Each write is followed by a read-back, up to five reads 1.5 s apart, and
  the outcome is `DONE` only when the account shows the change; otherwise `NOT_CONFIRMED`, `FAILED`,
  `SIGNED_OUT` or `NOTHING_TO_SAVE`, each said in a toast and logged under `[favourites]` with the item,
  the intended change, the server's answer and how many reads it took.
- Follow reuses the subscription seam (`AccountSubscriptions.setSubscribed`, through `AccountFollows`), so
  an artist followed here is a channel subscription everywhere, including the follow marks (ADR 22). Its
  state comes from the subscription list by channel id.
- A song's like is read when its long-press menu opens (`next` as the TV client, as the player already
  does), not from the liked list.
- Save on the album page and Follow on the artist page; removing or unfollowing asks first, as an
  unsubscribe does (ADR 22). Like toggles straight away, as in the player.
- Writes run in the application scope, so leaving the page or closing the menu does not cancel one.
- The Music home's "Your favourites" replaces the library's separate albums and artists sections: Liked
  songs (opens Liked Music), Saved albums, Followed artists.

## Consequences

- A favourite made in Totum is a real account change, visible in YouTube Music, and a write the account
  never shows is never reported as done.
- A slow account can make a successful change take up to about 6 s to confirm; past that it says "not
  confirmed" even if it lands later.
- Followed artists are matched by name for the read-back, because the library list carries no ids.
