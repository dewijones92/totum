---
title: The AI service decides which tools a caller may use
kind: todo
status: built 2026-11-06 — in the service and its unit tests; not yet deployed to the Pi
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# The AI service decides which tools a caller may use

Found 2026-11-06: `totum ai-serve` takes `tools` from the caller unchecked (`ai/Completion.kt`, `AiServer.kt:59-66`)
and passes them to Claude Code. Decided: a server-side allow-list per caller tag; anything else refused and logged.
Must land before ✨ Ask sees the app.

Whole-batch review: the allow-list holds a caller table for every planned AI caller (ask, vet, file, ask-state, for-you, digest, transcript, spoiler).

## Built, 2026-11-06

`CallerTools.STANDARD` in `cli/…/serve/ai/` is the one table; `AiServer` refuses a tool or caller outside it with 403
and a 🚫 message, logs caller, asked and allowed tools, and records the refusal as a usage failure. Totum's calls now
go out as `ask`, `vet` and `file` (service `totum-fetch` in `meta`). `ask-state` is `Bash(jq:*)` only; `for-you`,
`digest`, `transcript` and `spoiler` start with no tools. [ADR 121](../adr/0121-the-ai-service-decides-each-callers-tools.md).
Deploy order matters one way: an old totum-fetch still calls as `totum-fetch`, which the new table does not know, so
update totum-fetch first and the AI service second (a new totum-fetch against the old AI service works, as that
service ignores the tag).
