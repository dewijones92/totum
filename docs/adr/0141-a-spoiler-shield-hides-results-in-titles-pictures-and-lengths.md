---
title: A spoiler shield hides results in titles, pictures and lengths, through one title seam
kind: adr
status: accepted
updated: 2026-11-07
---

# 141. A spoiler shield hides results in titles, pictures and lengths, through one title seam

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-05: for chosen channels (ATP, WTA, Tennis TV) Haiku rewrites titles without the result, thumbnails blur
and lengths hide until watched; a per-source switch; the real title stays a tap away; dry runs on real titles. The
2026-11-06 whole-batch review added that the shield owns the **title display policy** the rest of Batch A uses, so that
every surface shows what the shield decides.

The dry runs showed something the brief did not say: **plain titles leak the winner by the order of the names.** The
ATP's own highlights are titled "Rinky Hijikata vs Novak Djokovic Highlights | Paris 2026 Round 2" with no result word
in them, while Tennis TV's title for the same match says "Qualifier Rinky Hijikata Takes Down Novak Djokovic": the
winner is named first. Hiding only words such as "beats" would have left every ATP title telling the result.

## Decision

### The seam

- **`ItemShield` and `ItemFace` in `:core:domain`.** `face(item)` answers what to show for an item: its title, its
  thumbnail, its length, whether the picture is blurred and whether it is shielded. `NoShield` shows the item as it is;
  `titleOf(item)` is the one-liner everything non-visual uses. `ItemFace.artworkUrl` is null for a shielded item, for
  places that cannot blur (a notification's large icon, the lock screen).
- **`SpoilerShield` in `:core:data` implements it**, and is created once in `AiFeatures` (the app's AI features hold the
  shield, the transcript digests and the morning job). Composables read it through `LocalItemShield`, provided with the
  other row capabilities in `ProvidePlayStates`; because the provided value changes identity whenever the shield's state
  does, every row recomposes when a safe title arrives.
- **Surfaces now read the seam:** every `MediaItemRow` (title, picture, length), the action sheet's title and the
  Details lines (the length and the description are hidden while shielded), Carry on, the playback session's metadata
  (`Media3PlaybackController.metadataFor`: so the mini player, the full player, the notification and the lock screen
  say the safe title, carry no description and no artwork), the "starting" bar and the refused-tap toast
  (`PlayAttempts`), the Next line (`nextLineOf(titleOf = …)`, re-evaluated when the shield changes), Share, Copy link and
  Share list, the not-for-me toast, and the group-news, new-upload and morning notifications (`LiveNoticeLabels`).
- **Not covered, said plainly:** the player's seek bar (the length is the player's own), the download notifications and
  the Downloads page rows that do not use `MediaItemRow`, search-result rows built from a `SearchHit` rather than an item,
  the queue's own "already queued" notices, the cast TV's title (the session metadata carries the safe one, but the
  Cast receiver may still show the stream's own name), and the watch-page description the Details tab can fetch itself.
  Each is a one-line change to read the seam; none was done blind. The log lines keep real titles on purpose.
- **A new surface asks the seam, never the item.** The Ask builder's state pack and the digest should call
  `titleOf`.

### The switch and "until watched"

- A per-channel switch: the 🙈 icon in a channel page's header, and "Hide spoilers from this channel" in any video's
  action sheet. A channel is matched by its YouTube channel id, or by its name when an item names no link. Nothing is
  shielded until Dewi chooses a channel; no channel is preselected.
- A shielded item shows the real title again when it has been **played** (the same play state every list uses) or when
  **revealed by hand** ("Show the real title" in its sheet, "Hide the title again" undoes it).

### The model, and what is enforced in code

- A `spoiler` AI job (caller `spoiler`, no tools, [ADR 121](0121-the-ai-service-decides-each-callers-tools.md)): the
  phone sends batches of up to 25 `(id, title, channel)`; Haiku answers `(id, spoils, safeTitle)`. The prompt keeps
  the players, tournament, year, round and kind of video; removes every hint (winner, score, set count, comeback, upset,
  retirement, who is through, and hype such as "epic" or "blockbuster", which tell how close it was); and **puts two
  players in alphabetical order of surname joined by " v ", always**, because order is a leak.
- **Fail closed.** Until a safe title is known the item shows "🙈 Title hidden". A failed check leaves it hidden and is
  tried again after 10 minutes (the next time it is seen); a missing verdict is the same. Nothing falls back to the real
  title.
- **`SpoilerRules.decide`** runs on the Pi after the call and again on the phone: a regex of result words, hype words,
  scores and "d."/"def." beats a model that says "fine"; a rewrite that still names a result, shares no word with the
  title (invented) or is much longer than the title is replaced by the neutral title.
- The AI-job plumbing is new and shared: `POST /ai/<task>` starts a job and `GET /ai/job/<id>` reports it
  (`AiTaskRoutes`, `HttpAiJobs`), for this feature, the transcript digest and the morning job
  ([ADR 142](0142-tl-dr-chapters-and-ask-about-this-video-are-one-transcript-digest.md),
  [ADR 143](0143-for-you-and-the-morning-digest-are-one-morning-job.md)).

## Dry runs (2026-11-07, Haiku through a local `totum serve` built from this branch)

Inputs: **104 real, public video titles** from seven channels (ATP Tour 18, Tennis TV 24, Wimbledon 24, WTA 8,
Roland-Garros 12, US Open 6, Premier League 8) plus 4 Kurzgesagt titles as a non-sport control, read with yt-dlp the
same day. Sent in batches of 25; 20 to 46 s a batch.

- **Run 1** (first prompt): spoiling titles were mostly rewritten well, but **31 of the 48 titles naming two players
  came back unchanged** (every plain "A vs B" ATP title among them), so the order leaked. "BLOCKBUSTER", "ICONIC battle", "UNFORGETTABLE Final" were left.
  Rule gaps seen in the same run: "five-set", "retaining his Championship", "saves THREE championship points" (all
  added). "Winners That Are Out of This World!" (a shot compilation) and "CHAMPIONS IN THE MAKING" were falsely hidden
  by the plural "winners" and "champions" (plural winners removed; "champions" stays, see below).
- **Prompt change:** always put two named players in alphabetical order, answer spoils true whenever anything changed
  (even only the order), and add the hype words to the list.
- **Run 2:** 65 rewritten (4 of them hidden by the neutral title), 39 kept as written; **of the 48 titles naming two
  players, 3 came back unchanged and they were already in alphabetical order** ("Alcaraz v Sinner: In the Shadows of
  Giants"); no missing verdicts. Right by eye: "Qualifier Rinky Hijikata Takes Down Novak Djokovic" became
  "Novak Djokovic v Qualifier Rinky Hijikata | Paris 2026 Highlights"; "Carlos Alcaraz GOD MODE COMEBACK vs Fritz" became
  "Carlos Alcaraz v Fritz | Paris 2026 Highlights"; "Alexander Zverev shares a laugh after Gentlemen's Singles Final
  defeat" lost "defeat"; the ATP's "Rinky Hijikata vs Novak Djokovic" and "Taylor Fritz vs Arthur Fils" now read the same
  on the ATP and Tennis TV channels. The 39 kept as written were draws, "Road To Turin" features, WTA day summaries and the football and
  Kurzgesagt controls (one Kurzgesagt title is below).
- **Wrong or soft spots, kept honest:** "Luca Van Assche" sorted under V (a multi-word surname; the order is still a
  function of the names only, so it still hides the result); "Jannik Sinner's tie-break" (after dropping "MASTERCLASS")
  still says Sinner owned the tie-break; "Portrait of a Wimbledon Champion … Final Revisited" lost the word champion but
  still names a final; a champions' dinner video exists only after the tournament, which no title edit can hide;
  "CHAMPIONS IN THE MAKING" (a Roland-Garros documentary series) and Kurzgesagt's "How I Survived Late-Stage Cancer" are
  hidden by the rules (false positives: the cost of a rule that wins over the model is a title shown as "🙈 Title hidden",
  one tap from the real one, on a channel the person chose to shield).
- Model cost 0.7 to 1.4 cents of API-equivalent per 25-title batch; one batch per screenful, once per video, kept.

## Consequences

- A shielded channel's list shows "🙈 Title hidden" for the second or two a first look takes, then the safe title; a
  phone with no home server shows it for ever (a reveal still works).
- The shield file (`spoiler-shield.json`: the channels, up to 3,000 safe titles, the revealed ids) is not in the Drive
  backup yet.
- `face()` has a deliberate side effect: seeing an unknown shielded item queues its title for a check. It is
  thread-safe and idempotent, and the only way to cover every list without a view model each.
- The playback session's metadata is built when an item loads: a safe title that arrives after play started is not
  pushed in, and revealing while it plays shows the real title at the next play.
