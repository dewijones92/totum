---
title: An AI service on the Pi, and health the Pi judges
kind: adr
status: accepted
updated: 2026-11-01
---

# 113. An AI service on the Pi, and health the Pi judges

- Status: Accepted
- Date: 2026-11-01

## Context

Dewi, 2026-11-01: *"a dedicated container on the Raspberry Pi titled something like AI service that takes in prompts
and gives responses … reusable by other stuff in the Raspberry Pi … defaulted to Haiku … Haiku is the only thing in
that"*, and *"a health dashboard … in the private area … health of stuff, Totum on the Raspberry Pi … and also health
of the money app … keep this dry"*, *"the usage of the Claude Code container … graphs"*, and from the home-download
research, home health alerts and the Pi's free space on the phone. He chose: the AI service only for services on the
Pi; Matrix and a phone banner when something breaks.

## Decision

- **`totum ai-serve`** (`cli/…/serve/ai/`) in its own container, `server/ai-service`, on `bin_private` with no port
  published: `POST /complete` takes a `Completion` (caller, what, system, message, JSON schema, tools) and answers the
  structured JSON; `GET /health` says the model, whether Claude is signed in (as last seen), and today's calls and
  failures; `GET /usage?days=N` sums every call by day and by caller (tokens, the CLI's cost figure, the median time,
  failures) from a JSON-lines log, for the dashboard's graphs. Haiku only (`TOTUM_AI_MODEL`). Claude's sign-in
  (`CLAUDE_CODE_OAUTH_TOKEN`) lives in `/var/lib/ai-service/claude.env`, nowhere else.
- **Every problem with Haiku lands in one log** (Dewi: *"any sort of problems or errors that we have with Haiku …
  log … to that health dashboard … including the way Totum uses it and … other apps"*): the service records each
  call's failure itself (not signed in, timed out, no structured answer), and `POST /report` takes a problem a caller
  found afterwards (an answer that did not fit its shape, a refused filing plan), which Totum sends by itself.
  `/usage` lists the latest failures with their reasons.
- **One completion seam**: `Completer` with two adapters, `ClaudeCodeCompleter` (runs `claude -p`, used by the AI
  service and on the laptop) and `RemoteCompleter` (HTTP); Totum's prompts sit above it in `PromptBrain`.
  `totum serve` uses the AI service when `TOTUM_AI_URL` is set, so totum-fetch no longer contains Claude Code. Another
  provider later (the 2026-11-01 subscription discussion) is one more `Completer`.
- **One image, three stages** (`server/totum-cli/Dockerfile`: `cli`, then `ai` and `fetch`), so the release download,
  its checksum and the user are written once.
- **The Pi judges its own health** (`HomeHealth`): under 30 GB free, the AI service unreachable (broken) or not
  signed in to Claude, Jellyfin not answering, a download that has not moved for 10 minutes. The problems travel
  with every job list (`HomeJobs.problems`) and on `GET /health` with the free space. The phone shows them on a
  banner on every tab (`HomeProblemsBanner`, red when anything is broken) and notifies each new one once; problems
  already there when the app starts are shown, not announced. The Downloads → 🏠 At home section shows the space used
  and free at home, like the phone's.

- **Every call names its caller** (2026-11-01, Dewi: *"require that the incoming request has a tag, so I know if
  it's Totum using it or something else … maybe some other useful metadata"*): `caller` must be a tag of 2–40
  lowercase letters, digits or dashes (`totum-fetch` for Totum's Pi service) and `what` must say what the call is for,
  or the service answers 400 with the reason and Claude is never run. A call may also carry its `version`, a
  `requestId` (Totum sends one per attempt) and free-form `meta` (Totum sends the attempt number); all of it is logged
  and kept with the usage record, and `/usage` lists the last 20 calls with it.

## Consequences

- The dashboard (dot-files) only reads: totum-fetch's `/health`, the AI service's `/health` and `/usage`, and
  Aerarium's `/api/health`; none of the rules are written twice.
- A Pi without Claude's sign-in is now visible on the phone, as a warning, rather than only in a log.
