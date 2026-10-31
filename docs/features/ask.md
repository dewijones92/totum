---
title: ✨ Ask (find something to download by saying what you want)
kind: feature
status: refining
area: search
updated: 2026-10-31
---

# ✨ Ask

Say or type what you want ("a popular Gruffalo film my toddler would like"); Haiku on the home server picks 3–5
titles with web search, the app finds the best copy of each with its own searches, Haiku checks the copies are the
right film, and you confirm through the normal ⬇️ sheet. Refine by asking again ("something newer"). ADR 109.

## Where

- ✨ in every tab header beside 🔍, and the app icon's "✨ Ask" shortcut.
- 🎙️ uses Android's speech recogniser; typing works too; recent asks come back as chips.
- Each suggestion is a card: kind and title (year), why it fits, ✅ Already got it, the best copy as its ordinary
  search row, ❌ copies skipped with the reason, 🔍 All copies (ordinary search).
- The progress strip shows "✨ Asking: …" while it works, with Cancel.

## Seams

- Wire contract: `core/data/.../ask/Ask.kt` (one definition for the app and the CLI).
- Phone client: `HttpAskService` (`https://totum.<home>/fetch`, device token, POST then poll).
- Copies: `AskCopies` + `AskCard` (best copy, rejected copies); `AskViewModel`, `AskScreen` in `app/.../ui/ask/`.
- Service: `cli/.../serve/` (`AskServer`, `ClaudeCodeBrain`, `Prompts`), container in `server/totum-fetch/`.
- 10-bit preference: `ReleaseQuality.isTenBitHevc`, `TorrentSearchSource` (health bands), `decodesTenBitHevc`.

## Status

Built and tested against a fake brain and once against Haiku on the laptop. Waiting on Dewi's choice of Claude
account for the Pi (`claude setup-token`); until then the service answers "❌ …" and nothing else changes.

## Measuring it

`tools/finder-eval/eval.py --serve <url> --label <name>` runs `cases.json` (16 asks, 20 copies to vet) and writes
a scored report to `tools/finder-eval/out/`. `tools/finder-eval/fake-claude` stands in for Claude in tests.
