---
title: A model subscription of Dewi's own for the Pi, and a weekly check on the best value
kind: todo
area: pi/infra
priority: medium
status: to discuss 2026-11-01
updated: 2026-11-01
---

# A model subscription of Dewi's own for the Pi

Dewi, 2026-11-01 (03:40): *"Instead of using Haiku on the Pi, I want to potentially use some other service that's
personal to me … good open models on OpenRouter that are low cost, but … some subscriptions are really subsidized,
like Claude Code … comparable other subscriptions … a DeepSeek subscription … or an OpenRouter subscription … and
tracking … what the best subscription is as well, like a weekly agent … don't do anything now."*

## What uses a model on the Pi today

- ✨ Ask (ADR 109): `ClaudeCodeBrain` runs `claude -p --model haiku` with web search and a JSON schema.
- Filing into Jellyfin (ADR 111): the same brain, a structured plan per finished torrent; rules when it fails.
- Both go through one port, `Brain` (`cli/…/serve/Brain.kt`), so another provider is one more adapter.

## To discuss

- Which providers and plans: OpenRouter (pay-as-you-go open models), DeepSeek, other subsidised subscriptions.
- What Ask needs that the filing does not: web search, structured output.
- Judge candidates with the existing evals: `tools/finder-eval` (Ask) and `totum file-check` (filing, ADR 111).
- A weekly agent comparing price and quality and saying when to switch; where it runs and how it reports.
- Research is delegated to a Sonnet once the questions are agreed.

## Also for the morning (Dewi, 2026-11-01 08:35)

Whether to rework the Pi's dot-files to be easier to change: `generate.sh` needs the Bitwarden master password, so
every nginx change is hand-applied to the template AND the root-only `bin/` (six recorded deviations); single-file
bind mounts and nginx's inode pinning; one 1,020-line CLAUDE.md. Ideas: secrets the generator can read without him
(a root-only file, or sops/age in the repo), a site per file in a mounted `conf.d/`, CLAUDE.md split per service.
