---
title: A narrow mail importer for documents that arrive nowhere else
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0009 · A narrow mail importer

Amends [ADR 0002](0002-read-only-everywhere.md), which kept Gmail out of the app entirely.

## Context

Some money facts only ever arrive by email: payslips (sent to a second mailbox), insurance and broadband
renewal letters with their quoted prices, and bills with exact tariffs and usage. Dewi chose to have Aerarium
fetch these itself (2026-10-31), in v1, right after the bank connections.

## Decision

- A mail importer runs on the server. Each mailbox it reads is a connection with its own Gmail app password,
  stored encrypted ([ADR 0007](0007-secrets-encrypted-key-outside-backups.md)), separate from any other
  agent's credential so it can be revoked on its own.
- It opens `[Gmail]/All Mail` with `EXAMINE` (read-only) and fetches with `BODY.PEEK`, so nothing is marked read,
  moved, deleted or sent. There is no code path that writes to a mailbox.
- It only ever searches **senders on an allow-list** Dewi edits in the app, each tagged with what it brings
  (payslip, renewal, bill). Nothing outside the list is opened.
- Attachments (PDFs) are stored under the data directory and read locally; payslips yield gross pay, tax,
  National Insurance, pension contributions and net pay. Identifiers printed on documents (NI number, employee
  number, policy numbers) are never stored in the database or shown.
- **Who reads what** (Dewi, 2026-10-31: "if it needs an LLM to make decisions, put it in the weekly thing"):
  - Payslips come from one sender in one layout, so a deterministic parser reads them on arrival. No LLM.
  - Renewal letters and bills vary by company, so the importer only downloads them, redacts personal
    identifiers locally (names, addresses, account, policy and meter numbers, plus the denylist), and queues
    them. The **weekly Sonnet run** (phase 2, the deal scout's container) extracts the facts (provider, renewal
    date, quoted price, tariff rates) and posts them back as structured data.
  - The weekly run may suggest new senders for the allow-list; Dewi approves each one in the app.
- The importer is a source like any other: each run is logged and appears on the health dashboard.

## Consequences

An app password is full-access by nature, so the read-only guarantee for mail is in Aerarium's code (and its
tests), not in Google's scopes. That is the same trade the Hermes Gmail bridge made, accepted knowingly.
