import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { MONZO_ALLOWED, MonzoApi, MonzoConnector } from '../src/connectors/monzo.ts';
import type { ConnectionRecord } from '../src/store/connections.ts';
import { testApp } from './helpers.ts';

const log = pino({ level: 'silent' });
const now = new Date('2026-11-01T12:00:00Z');
const creds = {
  clientId: 'oauth2client_1',
  clientSecret: 'mnzconf.s3cr3t',
  redirectUrl: 'https://aerarium.example.com/api/monzo/callback',
};

interface Call {
  method: string;
  path: string;
  query: URLSearchParams;
  body: string | null;
}

function tx(i: number, extra: Record<string, unknown> = {}) {
  return {
    id: `tx_${String(i).padStart(4, '0')}`,
    created: '2026-10-01T09:00:00Z',
    amount: -100 - i,
    currency: 'GBP',
    description: `SHOP ${String(i)}`,
    merchant: { name: 'Example Shop' },
    ...extra,
  };
}

function fakeMonzo(calls: Call[], opts: { forbid?: boolean } = {}) {
  return (url: string, init: RequestInit) => {
    const u = new URL(url);
    calls.push({
      method: init.method ?? 'GET',
      path: u.pathname,
      query: u.searchParams,
      body: typeof init.body === 'string' ? init.body : null,
    });
    const ok = (b: unknown, status = 200) =>
      Promise.resolve(new Response(JSON.stringify(b), { status }));
    if (u.pathname === '/oauth2/token')
      return ok({ access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 3600 });
    if (opts.forbid) return ok({ code: 'forbidden.insufficient_permissions' }, 403);
    if (u.pathname === '/accounts') {
      return ok({
        accounts: [
          { id: 'acc_1', description: 'Example current', type: 'uk_retail', currency: 'GBP' },
          { id: 'acc_old', closed: true },
        ],
      });
    }
    if (u.pathname === '/balance') return ok({ balance: 12345, currency: 'GBP' });
    if (u.pathname === '/pots') {
      return ok({
        pots: [
          { id: 'pot_1', name: 'Holiday', balance: 5000, currency: 'GBP' },
          { id: 'pot_x', name: 'Gone', balance: 0, currency: 'GBP', deleted: true },
        ],
      });
    }
    if (u.pathname === '/transactions') {
      const since = u.searchParams.get('since') ?? '';
      if (since.startsWith('tx_'))
        return ok({ transactions: [tx(100), tx(101, { decline_reason: 'INSUFFICIENT_FUNDS' })] });
      return ok({ transactions: Array.from({ length: 100 }, (_, i) => tx(i)) });
    }
    return ok({}, 404);
  };
}

const connection = (secrets: Record<string, unknown>): ConnectionRecord => ({
  id: 'm',
  kind: 'monzo',
  label: 'Monzo',
  createdAt: now.toISOString(),
  consentExpiresAt: null,
  config: {},
  secrets,
});

describe('MonzoConnector', () => {
  it('reads accounts, live pots, and pages through settled transactions', async () => {
    const calls: Call[] = [];
    const api = new MonzoApi(() => creds, log, fakeMonzo(calls));
    const r = await new MonzoConnector(api, log).sync(
      connection({ accessToken: 'a', refreshToken: 'r', expiresAt: '2026-11-02T00:00:00Z' }),
      now,
    );
    expect(r.accounts.map((a) => [a.externalId, a.kind, a.balance?.minor])).toEqual([
      ['acc_1', 'current', 12345],
      ['pot_1', 'savings', 5000],
    ]);
    expect(r.transactions).toHaveLength(101);
    expect(r.transactions[0]).toMatchObject({
      externalId: 'tx_0000',
      minor: -100,
      counterparty: 'Example Shop',
      bookedOn: '2026-10-01',
    });
    const pages = calls.filter((c) => c.path === '/transactions');
    expect(pages.map((c) => c.query.get('since'))).toEqual(['2023-11-02T12:00:00.000Z', 'tx_0099']);
    expect(r.secrets).toBeUndefined();
    expect(r.config).toEqual({ syncedThrough: { acc_1: '2026-10-22T12:00:00.000Z' } });
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
  });

  it('refreshes an expiring token first and hands the new one back to be stored', async () => {
    const calls: Call[] = [];
    const api = new MonzoApi(() => creds, log, fakeMonzo(calls));
    const r = await new MonzoConnector(api, log).sync(
      connection({ accessToken: 'old', refreshToken: 'r', expiresAt: '2026-11-01T12:01:00Z' }),
      now,
    );
    expect(calls[0]).toMatchObject({ method: 'POST', path: '/oauth2/token' });
    expect(new URLSearchParams(calls[0]!.body ?? '').get('grant_type')).toBe('refresh_token');
    expect(r.secrets).toEqual({
      accessToken: 'new-access',
      refreshToken: 'new-refresh',
      expiresAt: '2026-11-01T13:00:00.000Z',
    });
  });

  it('says to approve in the Monzo app when access is refused', async () => {
    const api = new MonzoApi(() => creds, log, fakeMonzo([], { forbid: true }));
    await expect(
      new MonzoConnector(api, log).sync(
        connection({ accessToken: 'a', refreshToken: 'r', expiresAt: '2026-11-02T00:00:00Z' }),
        now,
      ),
    ).rejects.toThrow(/approve Aerarium in the Monzo app/);
  });

  it('can never call a pot deposit or any write besides the token exchange', async () => {
    const methods = new Set(MONZO_ALLOWED.map((a) => `${a.method} ${a.path.source}`));
    expect([...methods].filter((m) => !m.startsWith('GET'))).toEqual(['POST ^\\/oauth2\\/token$']);
    const calls: Call[] = [];
    const api = new MonzoApi(() => creds, log, fakeMonzo(calls));
    await expect(api.http.json('POST', 'https://api.monzo.com/pots/pot_1/deposit')).rejects.toThrow(
      /read-only/,
    );
    expect(calls).toEqual([]);
  });
});

describe('Monzo routes', () => {
  it('stores the developer client sealed, signs in, and creates one connection', async () => {
    const calls: Call[] = [];
    const t = testApp({ publicUrl: 'https://aerarium.example.com', monzoFetch: fakeMonzo(calls) });
    expect((await t.call('GET', '/api/monzo/status')).body).toEqual({
      available: true,
      configured: false,
      redirectUrl: 'https://aerarium.example.com/api/monzo/callback',
    });
    await t.call('POST', '/api/monzo/credentials', {
      clientId: 'oauth2client_1',
      clientSecret: 'mnzconf.s3cr3t',
    });
    expect(JSON.stringify((await t.call('GET', '/api/monzo/status')).body)).not.toContain('s3cr3t');
    const { url } = (await t.call('POST', '/api/monzo/start')).body as { url: string };
    const u = new URL(url);
    expect(u.host).toBe('auth.monzo.com');
    expect(u.searchParams.get('client_id')).toBe('oauth2client_1');
    const state = u.searchParams.get('state')!;
    const res = await t.app.request(`/api/monzo/callback?code=abc&state=${state}`);
    expect(res.headers.get('location')).toMatch(
      /^\/connections\?connected=Monzo&renewed=0&approve=monzo&id=/,
    );
    const exchange = new URLSearchParams(calls.find((c) => c.path === '/oauth2/token')!.body ?? '');
    expect(exchange.get('grant_type')).toBe('authorization_code');
    expect(exchange.get('redirect_uri')).toBe('https://aerarium.example.com/api/monzo/callback');
    expect(t.connections.synced().map((c) => c.kind)).toEqual(['monzo']);
    const replay = await t.app.request(`/api/monzo/callback?code=abc&state=${state}`);
    expect(replay.headers.get('location')).toMatch(/^\/connections\?failed=/);
  });

  it('is unavailable without PUBLIC_URL', async () => {
    const t = testApp();
    expect((await t.call('POST', '/api/monzo/start')).status).toBe(409);
  });
});
