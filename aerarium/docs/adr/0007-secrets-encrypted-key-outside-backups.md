---
title: Tokens encrypted in the database, keys kept out of backups
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0007 · Secrets

## Context

Dewi asked where the secrets are stored. The database is backed up (gpg-encrypted) to a private GitHub repo,
so a plain token column would put live bank tokens in every backup and every copy of the file.

## Decision

- Connection secrets (API keys, OAuth and session tokens) are stored AES-256-GCM encrypted in
  `connections.secrets_json` as `v1:<iv>:<tag>:<ciphertext>`.
- The key is a 32-byte base64 file named by `SECRETS_KEY_FILE`. On the Pi the key files (this one, the Enable
  Banking signing key, the Monzo client secret) live in `/var/lib/aerarium-keys/`, mounted read-only and
  deliberately **not** in the Pi state backup. Dewi keeps a copy of each in Bitwarden.
- With no key file (dev, tests, e2e) the server uses a throwaway key and says so at startup.
- Secrets are redacted from logs and never sent to the browser (`/api/connections` returns no secrets field;
  a test pins it).

## Consequences

A leaked backup or database copy holds no usable tokens. Losing the key costs reconnecting the banks, which the
90-day consent cycle makes routine anyway.
