---
title: ✨ Ask (a chat that finds anything to play or download, knowing your taste)
kind: feature
status: refining
area: search
updated: 2026-11-07
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

Chats are kept: Ask reopens the last one, and "🕘 Earlier chats" offers the others (newest 20), the latest 3 shown
with "Show all" to expand in place. ➕ New chat is always in the header (disabled while Ask is busy). Reopening a
chat searches its turns again in one job that New chat or opening another chat cancels.

## Asking about your own app (2026-11-07)

The same chat answers questions about what is in the app ("which unfinished videos from my tennis group are in the
queue", "why didn't that video carry on", "what have I starred", "which settings might surprise me"). A Haiku router on
the Pi decides per message: **app** (jq on a snapshot), **web** (the picks above) or **both** (two separate calls). The
phone gathers a fresh snapshot before every ask (📦), sends it gzipped (📤), and an app answer says "🗂️ Answered from
your app's own data" and carries cards of your own items with ▶️ Play all / ➕ Queue all; Ask never acts by itself.
If the snapshot cannot be gathered or sent the ask still goes, web only, and says so. ADR 140.

- Snapshot: `AskStateSource` (`app/.../ui/ask/`) feeds `AppStateBuilder` (`core/data/.../ask/state/`); the schema is
  walked from the pack's own serializers (`StateSchema`, `@Describe`); the shared `ReportRedactor` (via `StateScrub`) removes secrets on the phone, seeded with the home
  host and token, and again on the Pi.
- Pi: `RoutedAsk`, `AskStates` (10 minutes, in memory), `PUT /ask-state`, `StatePrompts`; the AI service runs the
  `ask-state` call with `Bash(jq:*)` in a private temp directory.
- Dry runs: `totum ask-state-check <backup.json> "question" "+follow-up"` runs the whole path through a local AI
  service against a backup file.

## Tools

Ask's three jobs call the AI service as `ask` (web search and fetch), `vet` (no tools) and `file` (web search); the
service refuses any tool outside that caller's row. Reading the app's own state runs as `ask-state` with jq only, and
the router as `ask-route` with none. ADR 121, ADR 140.

## Measuring it

`tools/finder-eval/eval.py --serve <url> --label <name>` runs `cases.json` (16 asks, 20 copies to vet) and writes
a scored report to `tools/finder-eval/out/`. `tools/finder-eval/fake-claude` stands in for Claude in tests.

A video result's **Go to channel** opens the channel through the shell (`LocalOpenSource`); it was a no-op until
2026-11-06.

## Forgetting an earlier chat can be undone (2026-11-06)

The ✕ on an earlier chat forgets it and offers Undo on the shell's snackbar (`AskViewModel.forget` returns the chat,
`unforget` puts it back in its place by date).

## Not for me (2026-11-06)

Items marked 🙅 Not for me are sent to the AI with the titles it should not suggest, and any suggestion whose label
matches one is dropped before searching (`AskViewModel`, logged as "left out: not for me").

## Tests owed (2026-11-06 consistency sweep)

A video result's Go to channel opening the channel (instrumented).
