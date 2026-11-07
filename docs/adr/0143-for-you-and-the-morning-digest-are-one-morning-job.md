---
title: 🤖 For you and the 🌅 morning digest are one morning job
kind: adr
status: accepted
updated: 2026-11-07
---

# 143. 🤖 For you and the 🌅 morning digest are one morning job

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-05: each morning Haiku picks about ten videos and podcasts from outside his subscriptions, from what he
watches, stars and likes, found by the app's own searches and shown as a "🤖 For you" group in Library; and one
notification each morning saying in two lines what is new across his groups ([ADRs 94 and 95](../adr/_index.md), per-group
switches [ADR 118](0118-every-new-content-notification-can-be-switched-off.md)). 2026-11-06: with the digest on, the
per-video "new upload" notifications stop and the digest replaces them; urgent ones still come through. The whole-batch
review made it one job and one notification, with `tasteOf` moved out of the Ask screen's package and given stars and
likes.

## Decision

- **One job, on the phone, calling the Pi twice.** `MorningWorker` is a `PeriodicJob` ([ADR 129](0129-periodic-jobs-remember-their-last-run-and-data-is-counted-from-the-uid.md)),
  daily, first run at the next 07:30, on any network; "Last ran 3 hours ago: 7 picks · 4 new" in Settings → Morning, and a
  "Run the morning job now" button that runs the same code in the progress strip. Both features are **off by default**
  (each is a switch), because each costs model calls and the digest changes which notifications arrive. The Pi keeps
  no state and has no schedule: it answers two tasks, `for-you` and `morning-digest` (callers `for-you` and `digest`,
  no tools, [ADR 121](0121-the-ai-service-decides-each-callers-tools.md)), over the shared AI job routes
  ([ADR 141](0141-a-spoiler-shield-hides-results-in-titles-pictures-and-lengths.md)).
- **For you.** `tasteOf` is now in `:core:data` (`Taste` gained `starred` and `liked`: the ⭐ Starred playlist's titles and
  the first page of the account's Liked videos, one polite YouTube request). The model names up to 6 `youtube` and 4
  `podcast` picks as `Suggestion`s (the shape ✨ Ask already uses, so `AskCopies` finds them), with no web: a name it is sure
  exists. `MorningRules.tidyForYou` drops, on the Pi, a kind that is not a video or podcast, a pick that is a followed
  channel or show, anything already owned or picked in the last 14 days, a repeat, and strips links from the reason.
  The phone then finds each pick **one at a time** with the app's own search (a video not from a followed channel, not a
  Short, not members-only; a podcast's newest episode through the feed preview) and replaces the **"🤖 For you"
  playlist** with what it found. A playlist, not a source group: a group holds channels, this holds videos, and a
  playlist is already in Library, in the backup and playable as a queue.
- **The digest.** While it is on, `GroupNews` hands each unannounced video to an inbox (`morning.json`) instead of a
  notification (so a group's bell still silences it, and a video is told once). At 07:30 the titles since the last
  digest go to Haiku grouped by group, a title hidden by the spoiler shield sent hidden; the model's two lines are kept only
  if they name a group or a title that was given (an invented line is dropped); if the Pi cannot be reached or gives
  nothing, plain lines are built in code so the morning is never silent. One notification, "🌅 Your morning", carries the
  lines and "🤖 7 picks for you are in Library". Nothing new and nothing picked sends nothing.
- **My call, not Dewi's:** only the per-video **group** notifications are replaced. The subscription "new uploads" and
  "new episodes" notifications (the other half of ADR 118) stay as they are: they are per source, not "across your
  groups", and removing them would leave non-group subscriptions with no notice at all.
- The switches, the inbox and the recent picks are in `morning.json` (not in `AppPreferences`, to keep that file out of
  this change; not in the Drive backup yet).

## Dry runs (2026-11-07, Haiku through a local `totum serve` built from this branch)

Inputs: a made-up household (13 well-known public channels including two for a toddler, 4 shows, 8 most-played names, 4
starred and 4 liked titles; no personal data), asked twice (the second with the first day's picks listed), and the digest
from four groups made of real public titles (tennis, with one title hidden by the shield, football, science, and an
empty one), one group alone, and a group whose title is an injection.

- **For you, first morning** (22 s): 5 videos and 3 shows; none from a followed channel or show; reasons tied to the
  taste. **Second morning** (26 s): 3 videos and 4 shows, **none repeated from the first**.
- **A flaw the first prompt had:** a reason said "You played Intro to Large Language Models" for a video that was only a
  pick from yesterday, never played. The prompt now says to tie a pick only to something really in the lists and never to
  claim they watched, starred or liked anything that is not.
- **Re-run with that prompt** (all three calls 5 to 24 s): 4 videos and 4 shows the first morning and 3 and 4 the
  next, **none from a followed channel or show, none repeated**, and every reason now cites only things in the lists
  ("Builds on the neural network ideas in the video you starred", "You like a megaproject and a bridge build video…").
  The digest lines were the same shape as before, and the injection group again gave none.
- **Digest** (5 to 8 s): "Tennis has four new videos, including Alcaraz v Fritz Paris 2026 highlights and Sinner v Zverev
  Wimbledon 2026 replay." and "Football has four new videos, …": two lines, the empty group ignored, the hidden title not
  mentioned. One group gave one line. The **injection** group gave no lines at all (the model declined), which the phone
  covers with a plain line built in code.
- Cost 0.3 cents per For you call and 0.07 to 0.13 cents per digest (API-equivalent).

## Consequences

- Picks the app cannot find are silently fewer than ten; the log says which and why.
- A Haiku pick can be wrong (a channel that has no such video): the search finds the nearest result, so a pick is a
  suggestion, never a claim that the exact title exists.
- The morning time is fixed at 07:30 for now.
- Deploying: no new container and no new setting. The Pi needs the new CLI release in `totum-fetch` (the routes), the AI
  service already knows the callers; the phone can update in any order (with the old Pi the job reports "the home server said
  404" and, for the digest, falls back to plain lines).
