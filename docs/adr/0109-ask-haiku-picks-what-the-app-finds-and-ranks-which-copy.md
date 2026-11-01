---
title: ✨ Ask — Haiku picks what, the app finds and ranks which copy
kind: adr
status: accepted
updated: 2026-11-01
---

# 109. ✨ Ask: Haiku picks what, the app finds and ranks which copy

- Status: Accepted (the Pi's Claude account is still Dewi's to choose; see Consequences)
- Date: 2026-10-31

## Context

Dewi: *"I want a feature … where I can say download a popular film related to the Gruffalo that my toddler would
like, and for it to actually search on the web and then search for torrents … and come back for me to confirm."*
He wanted it to run as a headless Haiku on the Pi, through the CLI, and to cover anything the app can download.
His favourite encode is 10-bit HEVC (x265).

## Decision

- **Where it runs.** `totum serve` (the CLI) on the Pi, in the `server/totum-fetch` container, with Claude Code in
  the same image. nginx routes `totum.<home>/fetch/` to it behind the per-device token (ADR 90). A POST starts a job
  and `GET /job/{id}` reports it, so a phone on a weak signal never holds a request open for the minute an answer
  can take. The same service will carry downloads at home.
- **Haiku picks what.** `claude -p --model haiku` with a fixed system prompt, a JSON schema (`--json-schema`) for
  3–5 suggestions (kind, title, year, why, search words, already have), and only WebSearch and WebFetch; it must
  confirm every title and year in a search result. The phone sends the conversation so far (refinements like
  "something newer") and up to 200 titles it already has (downloads and plays). One retry when the answer is
  malformed; 150 s per attempt.
- **The app finds and ranks which copy.** Each suggestion goes through the app's own search for its kind: films and
  series to the torrent sources, YouTube to video search, podcasts to iTunes, audiobooks to the archive and
  audiobook torrents (`AskCopies`). Ranking is the existing torrent ranking, so a copy sits in the same place here
  as in ordinary search.
- **Haiku vets the copies, nothing more.** The top 6 copies per suggestion go back in one call with no tools at
  all: is each the right work (not a sequel, stage show, compilation, dub…)? A rejected copy is skipped with its
  reason shown; the best copy is the first one not rejected, in the app's order. A failed check leaves the app's
  order standing.
- **10-bit x265 first, everywhere.** Within each health band of seeders (none, 1–4, 5–19, 20+), a 10-bit HEVC copy
  sorts first, in all torrent results, not only Ask's; flagged copies still sort last. A phone with no Main10 HEVC
  decoder keeps the plain order. The quality chip says "10-bit".
- **Confirm through the normal paths.** A card's row is the search row for its kind, so ✅ is the same ⬇️ sheet,
  episode picker or subscribe as everywhere else; "All copies" opens ordinary search with the same words.

- **A chat about anything, with your taste** (2026-11-01, docs/todos/ask-everything.md): five more kinds (song, album,
  artist, radio as 2–4 seed songs, latest of a followed channel or podcast); a `reply` in the answer, carried in later
  turns; a `Taste` in the request (subscribed channels and podcasts, followed artists, saved albums, the songs and the
  channels or shows played most over 90 days; liked songs are only a count in the API, so the most-played songs stand in
  for them); web search when Haiku needs a fact (film years, new releases), not always. Music, radio and latest are not
  vetted. Two rules Haiku kept breaking are enforced after the call (`AskAnswer.tidied`): a year only on films, series
  and audiobooks, a podcast you already follow becomes its latest, and markdown links are stripped from the reply.
- **Dry runs** (2026-11-01, local `totum serve` with Haiku, a hand-made taste): 5 rounds, 34 asks including follow-ups.
  Round 1 showed a followed podcast suggested as a show, the most-played vlogger left out of "the latest vlogs", and
  invented album years; the prompt changes fixed the vlogger, the years and the podcast needed the code rule (rounds
  2–4 were answered by a stale server still holding the port, found by its start time, and re-run as round 5). Round 5:
  the podcast ask gives its latest episodes, no album years, vlogs led by the most played, "only the travel ones"
  narrows correctly, no links.

## Consequences

- One brain call per ask (~7–20 s) and one per check (~3–5 s). Measured on the laptop on 2026-10-31: the
  Gruffalo ask in 7.4 s, five sensible titles; but Haiku skipped its web search and gave Zog as 2023 (it is 2018),
  which is why the prompt now insists on confirming years, and why `tools/finder-eval` exists: it scores asks for
  expected titles, kinds, years (against Wikidata), whether the app can find each one, and the vetting against
  names with known answers.
- Each ask uses a Claude account's quota (subscription) or credits (API key). Which account the Pi uses is
  Dewi's decision, pending: the laptop's Claude Code is on the Aptem team plan.
- Web pages can steer Haiku's suggestions but not act: it has no shell or files, and nothing downloads without ✅.
