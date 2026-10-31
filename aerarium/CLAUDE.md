# Aerarium

**Aerarium** (Latin: the Roman state treasury) is Dewi's personal finance hub: one read-only page for every
bank account, card, ISA, investment, pension, the mortgage and the bills. It runs on the Pi behind the Google
gate. Started 2026-10-31.

It is the **second app in the Totum repo** and is fully separate from Totum: its own folder, pnpm workspace,
docs, ADRs, CI workflow and releases ([ADR 0001](docs/adr/0001-a-second-app-in-the-totum-repo.md)).
Nothing here imports Totum and Totum imports nothing from here.

**Read the repo root `CLAUDE.md` too.** Its general rules bind this app: DRY and one seam per capability,
the testing pyramid (a bug fix's test seen red first), maximum compile-time safety, log generously with
`dewidebug`, every async operation visible, text wraps and never truncates, state strings start with an emoji,
living docs, and CRUD the ADRs in the same change as the code. Its Android, YouTube, Gradle and Totum-release
sections do **not** apply here. Where this file and the root disagree about Aerarium, this file wins.

## Non-negotiables

- **The repo is PUBLIC. No personal or financial data in it, ever.** No account numbers, sort codes,
  balances, payee names, provider names Dewi uses, emails, or real transactions, not even in tests or
  comments. Tests use synthetic data ("Example Bank"). Real data lives in `/var/lib/aerarium` on the Pi; the
  Gmail inventory lives in `~/.local/share/finance-hub/` on the laptop. `pnpm privacy` (pre-commit, pre-push and
  CI) enforces it with patterns plus a denylist kept outside the repo
  ([ADR 0004](docs/adr/0004-no-personal-data-in-the-public-repo.md)).
- **Read-only.** Aerarium never moves money. Providers are reached only through read access
  (Open Banking account information, read-scoped keys). Connectors use an HTTP client that refuses anything
  but the reads and token calls each connector declares
  ([ADR 0002](docs/adr/0002-read-only-everywhere.md)).
- **Owner only.** The Google gate admits more than one person, so the server also refuses any
  `X-Forwarded-Email` that is not `OWNER_EMAIL` ([ADR 0005](docs/adr/0005-owner-only-behind-the-gate.md)).
- **Money is integer minor units plus a currency**, never a float.
- **Nothing fails silently.** Every sync run is recorded; a source that breaks turns red on every page and
  says why ([ADR 0006](docs/adr/0006-a-health-dashboard-that-shouts.md)).

## Layout

| Path              | What                                                                                |
| ----------------- | ----------------------------------------------------------------------------------- |
| `packages/shared` | zod schemas and pure domain logic shared by server and web                          |
| `apps/server`     | Hono on Node 24, `node:sqlite`, connectors, scheduler, analysis; bundled by esbuild |
| `apps/web`        | React 19 + Vite, `wouter` routing, all UI strings in `src/strings.ts`               |
| `tools/`          | `preflight.mjs` (docs, versions), `privacy-guard.mjs` (the public-repo guard)       |
| `e2e/`            | Playwright against the built server with fake providers                             |
| `docs/`           | `adr/`, `features/`, `todos/`, `tests/`, each with an `_index.md`                   |

## Build, test, run

```bash
cd aerarium
pnpm install
pnpm gate        # preflight, privacy, format, lint, types, unit+coverage, build, e2e
pnpm test        # vitest only
PW_CHROMIUM_PATH=~/.cache/ms-playwright/chromium-1169/chrome-linux/chrome pnpm e2e   # Chromium 147+ cannot navigate on Dewi's WSL
```

CI is `.github/workflows/aerarium.yml` (paths `aerarium/**` only). Totum's `ci.yml` ignores `aerarium/**`,
so neither app's commits build, release or block the other.

## Gotchas already paid for

- TypeScript is pinned to 6.0.x (TS 7 ships no `tsserver.js`, and typescript-eslint supports `<6.1`).
