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
- **Joint is always labelled.** Wherever an account, transaction, bill or total appears, a joint account carries
  the 👥 Joint tag, and totals say whether they include joint money. A newly synced account is asked about once
  ("👤 Mine or 👥 Joint?") because banks do not reliably say.
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

## Deployment

- Every push to `main` that touches `aerarium/**`: gate → amd64 and arm64 images on native runners → one manifest at
  `ghcr.io/dewijones92/aerarium:{sha-xxxxxxx,v0.1.N,latest}` → `deploy` job (environment `aerarium-production`, `main`
  only, repo variable `AERARIUM_DEPLOY_ENABLED=true`).
- The deploy job SSHes to the Pi with a key whose `authorized_keys` line is `restrict,command="…/aerarium/deploy.sh"`
  (verified: no shell, no forwarding). `deploy.sh` pulls by digest, waits for `/healthz` to report the commit, and
  rolls back and fails CI otherwise.
- The Pi side lives in the dot-files repo (`~/code/dot-files/aerarium/`, README there). State in `/var/lib/aerarium`
  (backed up), keys in `/var/lib/aerarium-keys` (not backed up, copies in Bitwarden), `OWNER_EMAIL` and the Enable
  Banking settings in `/var/lib/aerarium/state/app.env`. nginx vhost on `/oauth2/auth-owner` passing
  `X-Forwarded-Email`. Read the dot-files root `CLAUDE.md` before touching anything on the Pi.

## Gotchas already paid for

- **Playwright `count()` does not wait, and `POST /api/connections/:id/sync` returns before the sync ends.** A
  test that counted rows straight after navigating passed every local run and failed in CI with 0. Wait for the
  run's outcome (`expect.poll` on `/runs`) and for a row to be visible before counting; prove a timing fix by
  delaying the request with `page.route` and watching the old test fail at the same line.
- TypeScript is pinned to 6.0.x (TS 7 ships no `tsserver.js`, and typescript-eslint supports `<6.1`).
