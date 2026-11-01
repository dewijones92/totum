---
title: ✨ Ask (a chat that finds anything to play or download, knowing your taste)
kind: feature
status: refining
area: search
updated: 2026-11-01
---

# ✨ Ask

Say or type what you want ("a popular Gruffalo film my toddler would like"); Haiku on the home server picks 3–5
titles with web search, the app finds the best copy of each with its own searches, Haiku checks the copies are the
right film, and you confirm through the normal ⬇️ sheet. Refine by asking again ("something newer"). ADR 109.

Since 2026-11-01 it is a **chat about anything** (Dewi: *"not specific to torrents … podcasts, music … radio station
based on happy funk … the latest vlogs … access to my subscriptions … multi-turn"*): your message as a bubble, Haiku's
one-or-two-sentence reply, then cards. Kinds: 🎬 film, 📺 series, ▶️ YouTube video, 🎙️ podcast (a show you do not
follow), 🎧 audiobook, 🎵 song, 💿 album, 🎤 artist, 📻 radio (seed songs, 📻 Start this radio) and 🆕 latest (the
newest uploads or episodes of a channel or podcast you follow, ▶️ Play all / ➕ Queue all). Your taste goes with each
ask: YouTube subscriptions, podcasts, followed artists and saved albums, and the songs and channels you played most in
90 days. Follow-ups ("less disco, more 70s") refine; ➕ New chat starts again.

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

Chats are kept: Ask reopens the last one, and "🕘 Earlier chats" offers the others (newest 20).

## Measuring it

`tools/finder-eval/eval.py --serve <url> --label <name>` runs `cases.json` (16 asks, 20 copies to vet) and writes
a scored report to `tools/finder-eval/out/`. `tools/finder-eval/fake-claude` stands in for Claude in tests.
