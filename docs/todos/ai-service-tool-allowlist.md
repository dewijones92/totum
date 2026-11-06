---
title: The AI service decides which tools a caller may use
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# The AI service decides which tools a caller may use

Found 2026-11-06: `totum ai-serve` takes `tools` from the caller unchecked (`ai/Completion.kt`, `AiServer.kt:59-66`)
and passes them to Claude Code. Decided: a server-side allow-list per caller tag; anything else refused and logged.
Must land before ✨ Ask sees the app.
