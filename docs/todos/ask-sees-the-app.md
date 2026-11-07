---
title: ✨ Ask can answer about your own app
kind: todo
status: built 2026-11-07, Batch A (tests owed)
batch: A (2026-11)
priority: high
updated: 2026-11-07
---

# ✨ Ask can answer about your own app

Dewi 2026-11-05/06, Batch A, built after the fixes. Haiku answers about the queue, history, downloads, groups,
subscriptions, stars, settings, stats and the event log; buttons only (it never acts by itself); event log included.
Dewi's design: one big JSON file, a JSON Schema generated from the app's own serializable classes (Swagger-style, with
short field descriptions and counts), and jq to query it. Reuse `Backup`/`BackupService.create` (most of the state
already serialises) plus downloads, stats and the trail. No tokens or keys; the folder is deleted after the answer.
Found 2026-11-06: `jq` is not in the ai image (`server/totum-cli/Dockerfile`); no schema generator exists; app state
holds untrusted text (titles, comments), so a question that reads the state runs with jq only, never web fetch
(prompt injection could otherwise send the data out) — the router decides per question. Needs the AI service
allow-list first and dry runs with real data (Full backup spike). Dewi 2026-11-06: ONE chat (the router decides per
question whether it needs the app data, the web or both); Haiku only, no "Dig deeper" with a stronger model; a fresh
snapshot with every question, kept on the Pi for 10 minutes so follow-ups reuse it.

## Spike, 2026-11-06 (laptop, Haiku, jq only, Dewi's real backup + that day's diagnostics)

One 4.9 MB JSON (queue 5,838, progress 1,101, history 50, groups, settings, 400 events) and a 12.9 KB schema with
field descriptions and counts, derived from the data for the spike. Six questions, one call each:

| Question | jq calls | Failed | Largest result | Time | Cost |
|---|---|---|---|---|---|
| Unfinished videos from the 🎾 tennis group in the queue | 2 | 0 | 1.1 KB | 14 s | $0.0014 |
| Unfinished Peep Show episodes, time at 2x | 8 | 0 | 15 KB | 49 s | $0.0099 |
| Chopin pianists with the most tracks | 4 | 0 | 5 KB | 26 s | $0.0038 |
| What have I starred? | 1 | 0 | 2.3 KB | 13 s | $0.0011 |
| Why didn't the WorldofAI video carry on from Chrome? | 3 | 0 | 8.8 KB | 32 s | $0.0051 |
| Which settings might surprise me? | 2 | 0 | 6.5 KB | 17 s | $0.0028 |

Checked against the data by hand: tennis (3 of 9 unfinished), starred (13) and the Chopin ranking were right; one
position was wrong (Hong Kong "about 5 min in", really 19 s). Peep Show was answered two ways across runs (YouTube
clips vs the torrent's 61 episodes), so an ambiguous question should get a one-line "I counted X; say if you meant
Y". The "why" answer found the real cause (no check against YouTube on resume). Haiku never read the whole file.

Lessons for the build: no magic values in the pack (the backup writes "played, time unknown" as
`completedAtEpochMs = 1`, which Haiku called a bad flag; use `played: true`); positions as minutes:seconds beside
the milliseconds; give the pack the item's group and episode numbers where known. A first run fed the remaining
questions through stdin to `claude -p` (always `< /dev/null`).

## Built 2026-11-07 ([ADR 140](../adr/0140-ask-answers-about-your-own-app-with-jq-only.md))

One chat with a Haiku router; a fresh gzipped snapshot before every ask, held 10 minutes in the Pi's memory; an app
question runs `Bash(jq:*)` alone on that snapshot, in a private temp directory in the AI service; "both" is two
separate calls (or app only with a note); the answer is text plus cards of the phone's own items. Dry runs with the
real model against Dewi's real backup are recorded in the ADR.

Reuse: `BackupService.create` for the file's own sections (queue groups, downloads, stats, trail and the event log
come from their stores), `episodeCodeOf`, `HttpAskService`/`HomePiCall`, the Ask cards (`ItemsBody` is `LatestBody`
generalised), `LatestBody`'s play / play all / queue all, the operations strip's ASK kind, `AskStateWire` for the
shared gzip frame, `StateScrub` on both ends.

## Tests owed

- An instrumented or screenshot check of an app answer on the emulator (the reply, the 🗂️ source line, a card of
  rows with play / play all / queue all) and of the packing and sending stages in the strip.
- A real phone to real Pi round trip: `PUT /fetch/ask-state` through nginx (needs `client_max_body_size` of at least
  16m on `/fetch/`) and a real `jq` in the rebuilt ai image (`jq` was installed by the Dockerfile, not yet built).
- `AskStateSource`'s real wiring (`AppContainer.askStateSource`) against a Room-backed container: the pure builder is
  tested, the suppliers are not.
- A queue group's id in `quietGroups`: the pack's channel groups carry no id (the backup format has none), so the model
  cannot say which group a quiet-group setting means.
- Downloads held at home on the Pi are not in the snapshot.
- The sandbox hardening in ADR 140 (bubblewrap) if Dewi wants jq unable to read the service's environment.
