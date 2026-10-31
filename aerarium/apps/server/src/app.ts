import { getConnInfo } from '@hono/node-server/conninfo';
import { serveStatic } from '@hono/node-server/serve-static';
import {
  AccountPatch,
  computeNetWorth,
  ManualBalance,
  NewManualAccount,
  type Connection,
  type Dashboard,
  type Healthz,
  type SyncStarted,
} from '@aerarium/shared';
import { Hono, type Context } from 'hono';
import { ZodError, z } from 'zod';
import type { FakeConnector } from './connectors/fake.ts';
import { buildHealthReport, type HealthDeps } from './health.ts';
import { isTrusted } from './http/ip.ts';
import { EMAIL_HEADER, judgeOwner } from './http/owner.ts';
import type { Logger } from './log.ts';
import type { AccountsStore } from './store/accounts.ts';
import type { ConnectionsStore } from './store/connections.ts';
import type { NetWorthDaysStore } from './store/net-worth-days.ts';
import type { TransactionsStore } from './store/transactions.ts';
import type { SyncEngine } from './sync/engine.ts';

export interface AppDeps {
  log: Logger;
  version: string;
  trustedCidrs: readonly string[];
  ownerEmail: string;
  staticDir?: string;
  clock: () => Date;
  schedulerLastTickAt: () => string | null;
  accounts: AccountsStore;
  connections: ConnectionsStore;
  transactions: TransactionsStore;
  netWorthDays: NetWorthDaysStore;
  engine: SyncEngine;
  health: Omit<
    HealthDeps,
    'accounts' | 'connections' | 'engine' | 'version' | 'schedulerLastTickAt'
  >;
  fake?: FakeConnector;
  remoteAddress?: (c: Context) => string | undefined;
}

class HttpError extends Error {
  constructor(
    readonly status: 400 | 404 | 409,
    message: string,
  ) {
    super(message);
  }
}

function remoteFromConnInfo(c: Context): string | undefined {
  try {
    return getConnInfo(c).remote.address;
  } catch {
    return undefined;
  }
}

const today = (d: Date): string => d.toISOString().slice(0, 10);

export function createApp(deps: AppDeps): Hono {
  const { log, accounts, connections, transactions, engine, clock } = deps;
  const remoteAddress = deps.remoteAddress ?? remoteFromConnInfo;
  const app = new Hono();

  app.onError((error, c) => {
    if (error instanceof ZodError) {
      log.warn({ path: c.req.path, issues: error.issues }, 'dewidebug rejected a request body');
      return c.json({ error: z.prettifyError(error) }, 400);
    }
    if (error instanceof HttpError) {
      log.warn(
        { path: c.req.path, status: error.status, error: error.message },
        'dewidebug request refused',
      );
      return c.json({ error: error.message }, error.status);
    }
    log.error({ path: c.req.path, err: error }, 'dewidebug unhandled error');
    return c.json({ error: 'Something went wrong on the server' }, 500);
  });

  app.use('*', async (c, next) => {
    const started = performance.now();
    await next();
    const ms = Math.round(performance.now() - started);
    const entry = { method: c.req.method, path: c.req.path, status: c.res.status, ms };
    if (c.res.status >= 500) log.error(entry, 'dewidebug request failed');
    else if (c.req.path.startsWith('/api/')) log.info(entry, 'dewidebug request');
  });

  app.get('/healthz', (c) => {
    const body: Healthz = {
      ok: true,
      version: deps.version,
      schedulerLastTickAt: deps.schedulerLastTickAt(),
    };
    return c.json(body);
  });

  app.use('*', async (c: Context, next) => {
    const ip = remoteAddress(c);
    if (!isTrusted(ip, deps.trustedCidrs)) {
      log.warn({ ip, path: c.req.path }, 'dewidebug refused: request not from a trusted network');
      return c.text('Forbidden', 403);
    }
    const verdict = judgeOwner(c.req.header(EMAIL_HEADER), deps.ownerEmail);
    if (verdict.kind === 'stranger') {
      log.warn(
        { email: verdict.email, path: c.req.path },
        'dewidebug refused: signed in, but not the owner',
      );
      return c.text('🔒 Aerarium is private to its owner.', 403);
    }
    await next();
  });

  const manualAccount = (id: string) => {
    const a = accounts.get(id);
    if (!a) throw new HttpError(404, 'No such account');
    if (a.sourceKind !== 'manual') {
      throw new HttpError(409, 'Only accounts you typed in can be changed by hand');
    }
    return a;
  };

  app.get('/api/dashboard', (c) => {
    const list = accounts.list();
    const since = new Date(clock().getTime() - 400 * 86_400_000);
    const body: Dashboard = {
      netWorth: computeNetWorth(list),
      accounts: list,
      history: deps.netWorthDays.since(today(since)),
    };
    return c.json(body);
  });

  app.get('/api/accounts', (c) => c.json(accounts.list()));

  app.post('/api/accounts/manual', async (c) => {
    const input = NewManualAccount.parse(await c.req.json());
    const now = clock().toISOString();
    const id = accounts.createManual(input, now);
    accounts.recordBalance(id, input.asOf, input.balanceMinor, input.currency, now);
    log.info({ id, kind: input.kind, owner: input.owner }, 'dewidebug manual account added');
    return c.json(accounts.get(id), 201);
  });

  app.patch('/api/accounts/:id', async (c) => {
    const id = c.req.param('id');
    if (!accounts.get(id)) throw new HttpError(404, 'No such account');
    const patch = AccountPatch.parse(await c.req.json());
    accounts.patch(id, patch);
    log.info({ id, fields: Object.keys(patch) }, 'dewidebug account changed');
    return c.json(accounts.get(id));
  });

  app.post('/api/accounts/:id/balance', async (c) => {
    const a = manualAccount(c.req.param('id'));
    const input = ManualBalance.parse(await c.req.json());
    accounts.recordBalance(a.id, input.asOf, input.balanceMinor, a.currency, clock().toISOString());
    log.info({ id: a.id, asOf: input.asOf }, 'dewidebug manual balance recorded');
    return c.json(accounts.get(a.id));
  });

  app.delete('/api/accounts/:id', (c) => {
    const a = manualAccount(c.req.param('id'));
    accounts.delete(a.id);
    log.info({ id: a.id }, 'dewidebug manual account deleted');
    return c.body(null, 204);
  });

  app.get('/api/accounts/:id/history', (c) => {
    const id = c.req.param('id');
    if (!accounts.get(id)) throw new HttpError(404, 'No such account');
    return c.json(accounts.balanceHistory(id));
  });

  app.get('/api/transactions', (c) => {
    const limit = z.coerce.number().int().min(1).max(5000).default(500).parse(c.req.query('limit'));
    const accountId = c.req.query('account');
    const since = c.req.query('since');
    return c.json(
      transactions.list({
        limit,
        ...(accountId ? { accountId } : {}),
        ...(since ? { since } : {}),
      }),
    );
  });

  app.get('/api/connections', (c) => {
    const body: Connection[] = connections.synced().map((x) => ({
      id: x.id,
      kind: x.kind,
      label: x.label,
      consentExpiresAt: x.consentExpiresAt,
      lastRun: connections.lastRun(x.id),
      lastOkAt: connections.lastOkAt(x.id),
    }));
    return c.json(body);
  });

  app.get('/api/connections/:id/runs', (c) => {
    const id = c.req.param('id');
    if (!connections.get(id)) throw new HttpError(404, 'No such connection');
    return c.json(connections.runs(id, 50));
  });

  app.post('/api/connections/:id/sync', (c) => {
    const conn = connections.get(c.req.param('id'));
    if (!conn || conn.kind === 'manual') throw new HttpError(404, 'No such connection');
    if (!engine.connectorFor(conn))
      return c.json({ outcome: 'no-connector' } satisfies SyncStarted, 409);
    if (engine.isRunning(conn.id)) return c.json({ outcome: 'busy' } satisfies SyncStarted, 202);
    void engine.run(conn, 'asked for in the app');
    return c.json({ outcome: 'started' } satisfies SyncStarted, 202);
  });

  app.delete('/api/connections/:id', (c) => {
    const conn = connections.get(c.req.param('id'));
    if (!conn || conn.kind === 'manual') throw new HttpError(404, 'No such connection');
    connections.delete(conn.id);
    log.info({ id: conn.id, kind: conn.kind }, 'dewidebug connection removed with its accounts');
    return c.body(null, 204);
  });

  app.get('/api/health', (c) =>
    c.json(
      buildHealthReport(
        {
          ...deps.health,
          accounts,
          connections,
          engine,
          version: deps.version,
          schedulerLastTickAt: deps.schedulerLastTickAt,
        },
        clock(),
      ),
    ),
  );

  if (deps.fake) {
    const fake = deps.fake;
    app.post('/api/dev/fake/connect', (c) => {
      const id = connections.create(
        { kind: 'fake', label: 'Example Bank (fake)' },
        clock().toISOString(),
      );
      log.info({ id }, 'dewidebug fake connection created');
      return c.json({ id }, 201);
    });
    app.post('/api/dev/fake/fail', async (c) => {
      const body = z.object({ error: z.string().nullable() }).parse(await c.req.json());
      fake.failWith = body.error;
      log.info({ failWith: body.error }, 'dewidebug fake connector failure mode changed');
      return c.json({ failWith: fake.failWith });
    });
  }

  if (deps.staticDir) {
    const root = deps.staticDir;
    app.use('/*', serveStatic({ root }));
    app.get('*', serveStatic({ root, path: 'index.html' }));
  }

  return app;
}
