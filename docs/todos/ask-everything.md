---
title: ✨ Ask about anything, as a chat that knows your taste
kind: todo
status: decided
area: ask
updated: 2026-11-01
---

# ✨ Ask about anything

Dewi, 2026-11-01: *"not make it specific to torrents … take my intent and search over anything, podcasts, music …
radio station based on happy funk music … the latest vlogs … access to my subscriptions … so it knows a bit what I
like … make it multi-turn"*.

Decided (AskUserQuestion, 2026-11-01):

- **A chat thread**, reachable from every tab's header: messages and answers alternate, each answer carries playable
  cards and actions; follow-ups refine it; ➕ starts a new chat; recent chats kept.
- **Cards; one tap acts**: a card plays or queues; lists get ▶️ Play all / ➕ Queue all; downloads and torrents still go
  through the usual ⬇️ question.
- **Taste sent with each ask**: YouTube subscriptions, podcast subscriptions, music likes and follows (followed
  artists, saved albums, a sample of liked songs), and what is actually played (top artists, channels and shows from
  the stats log, plus the recent titles).
- **Web search: Haiku decides** — answer from taste and the app's own searches first, the web only for facts (films,
  new releases).

Today (scout, 427eb1c5): kinds are film/series/youtube/podcast/audiobook (`AskKind`), no music or radio; turns are
resent by the phone as request text plus suggestion labels; taste is ~200 titles; `AskScreen` shows Song/Album/Artist
hits as bare text.
