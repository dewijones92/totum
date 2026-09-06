---
title: A shared link that cannot be resolved vanishes
kind: todo
status: fixed 2026-09-06 — option 1: queued by its id, resolved when it plays
area: share
priority: medium
requested: 2026-08-31
updated: 2026-08-31
---

# A shared link that cannot be resolved vanishes

Share a YouTube link while the connection is bad and **nothing happens**. No queue entry, no
message, no trace.

`MainActivity.handleShareIntent` resolves the URL before queueing it, so the queue entry carries a
real title rather than a URL. When `describe` returns null it gave up silently. Report 0.1.477 is
exactly that: `shared link -> …joyAbJ1pi3A` at 12:55:05, 53 seconds of yt-dlp retries against a
phone with no DNS at all, `genuinely unavailable`, and then nothing. The link was simply lost.

## Done so far

It now says so — `shared link could not be resolved, so nothing was queued -> <url>`. That makes it
diagnosable. It does not make it work.

## Fixed (2026-09-06) — option 1

`placeholderFor(url)` builds the entry from the watch URL alone (id, URL, a title of "YouTube video
<id>") and the share plays it like any other; the queue re-resolves from the URL when it plays, and the
player shows the real title because it plays the resolved item. Only the queue row keeps the
placeholder title until then. A URL that is not a video is still dropped, with a line saying so.
`SharedLinkTest` covers both. "Be bold" (Dewi, 2026-09-06) — the preference below was taken.

## The decision

Queueing it anyway is straightforward: the entry only needs the watch URL, which the share already
has, and both playback and the automatic download re-resolve from that URL when they need it. The
cost is a queue row whose title is a URL until something resolves it.

1. **Queue it anyway, titled with the URL.** A shared link never disappears. The queue gains a row
   that looks unfinished, and an item that is genuinely gone (deleted, private) now sits in the
   queue instead of never arriving.
2. **Leave it dropped, but tell the person** — a toast or a snackbar. Honest, no odd rows, but it
   needs the failure to reach the UI, and a share can arrive when no screen is showing.
3. **Leave it as it is.** The trail now explains it after the fact, which is enough if this only
   ever happens with no signal at all.

Preference is (1): the common cause is a bad connection, in which case the link is fine and the
resolve simply needs asking again later — which the queue already does. Flagged rather than
guessed, because it changes what appears in a shipped screen.
