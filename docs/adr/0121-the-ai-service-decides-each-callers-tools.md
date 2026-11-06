---
title: The AI service decides each caller's tools
kind: adr
status: accepted
updated: 2026-11-06
---

# 121. The AI service decides each caller's tools

- Status: Accepted
- Date: 2026-11-06

## Context

`totum ai-serve` (ADR 113) took `tools` from the caller and handed them to Claude Code unchecked, so any service on
`bin_private` could ask for web fetch or a shell. That is harmless while the only caller is Totum's own three jobs, and
wrong the moment ✨ Ask reads the app's own state (queue titles, comments and descriptions are text anyone can write):
a question that reads that data must never also be able to fetch a URL, or an injected instruction could send the data
out. Found in the 2026-11-06 whole-batch review of Batch A; it has to land before Ask sees the app.

## Decision

- **One table in the service**, `CallerTools.STANDARD` (`cli/…/serve/ai/CallerTools.kt`): caller tag → the tools that
  caller may use. `POST /complete` checks it after the tag's format and before anything runs.
- **A call may ask for fewer tools, never more.** A tool the caller may not use, or a caller tag the table does not
  know, is refused with **403** and a `🚫` message naming the caller, what it asked for and what it may use. The
  refusal is logged (`ai`: caller, what, request id, asked tools, allowed tools) and kept as a failure in the usage
  log, so it shows on the health dashboard like any other problem. Claude is never run. Totum's `RemoteCompleter`
  turns the 403 into `CallerRefused`, which `PromptBrain` does not retry (it would be refused again).
- **Tool strings are compared exactly**, split on commas outside brackets so `Bash(jq:*)` stays one tool.
- **The tag names the job, not the service.** Totum's Pi service used to send `totum-fetch` for every call; it now
  sends `ask`, `vet` or `file`, and says `service=totum-fetch` in the call's `meta`. The usage graphs therefore group by
  job rather than by service.

| Caller | Tools | Why |
|---|---|---|
| `ask` | `WebSearch`, `WebFetch` | Picking titles needs the web (ADR 109); its input is Dewi's own words and taste |
| `vet` | none | Judges copies from the names it is given |
| `file` | `WebSearch` | Looks up what a release is for filing (ADR 111); no fetch |
| `ask-state` | `Bash(jq:*)` | Reads the app's state file with jq only, no web tool of any kind: the state holds untrusted text |
| `for-you` | none | Picks from Dewi's taste, whose titles are untrusted; the app's own searches find each pick |
| `digest` | none | Summarises new video titles, which anyone can write |
| `transcript` | none | The most injectable input of all (what people said in a video); the phone sends the cues |
| `spoiler` | none | Rewrites titles; a web search would also be a spoiler source |

The last four start with no tools on purpose: widening one is a one-line change here and an edit to this table, made
when a dry run shows it needs the web, rather than a default to be taken back.

## Consequences

- A new AI job adds a row to the table in the same change as its code; an unlisted tag is refused, so forgetting is
  loud.
- Another service on the Pi calling the AI service needs a row too (nothing else calls it today).
- The table is in the service, so a caller cannot widen its own tools by editing its request.
- Deploy order matters one way: an old totum-fetch (caller `totum-fetch`) is refused by a new AI service, so update
  totum-fetch first; a new totum-fetch against the old AI service works, because the old service ignores the tag.
