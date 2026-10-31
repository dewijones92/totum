import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { Trading212Connector, TRADING212_ALLOWED } from '../src/connectors/trading212.ts';
import { ReadOnlyHttp, ReadOnlyViolation } from '../src/http/read-only.ts';
import type { ConnectionRecord } from '../src/store/connections.ts';
import { testApp } from './helpers.ts';

const log = pino({ level: 'silent' });
const conn: ConnectionRecord = {
  id: 'c',
  kind: 'trading212',
  label: 'Example ISA',
  createdAt: '2026-11-01T00:00:00Z',
  consentExpiresAt: null,
  config: { accountKind: 'isa-stocks', accountName: 'Example ISA' },
  secrets: { keyId: 'id', secret: 'sec' },
};

function fakeT212(calls: { method: string; url: string; auth: string | null }[]) {
  return (url: string, init: RequestInit) => {
    const headers = init.headers as Record<string, string>;
    calls.push({ method: init.method ?? 'GET', url, auth: headers['authorization'] ?? null });
    const u = new URL(url);
    let body: unknown;
    if (u.pathname.endsWith('/equity/account/summary')) {
      body = { id: 42, currency: 'GBP', totalValue: 12345.678, cash: {}, investments: {} };
    } else if (u.searchParams.get('cursor') === '2') {
      body = {
        items: [
          {
            type: 'WITHDRAW',
            amount: 50,
            currency: 'GBP',
            reference: 'r2',
            dateTime: '2026-09-01T10:00:00Z',
          },
        ],
        nextPagePath: null,
      };
    } else {
      body = {
        items: [
          {
            type: 'DEPOSIT',
            amount: 1000,
            currency: 'GBP',
            reference: 'r1',
            dateTime: '2026-10-10T08:00:00Z',
          },
        ],
        nextPagePath: '/api/v0/history/transactions?limit=50&cursor=2',
      };
    }
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
  };
}

describe('Trading212Connector', () => {
  it('reads the value and every page of deposits, with Basic auth', async () => {
    const calls: { method: string; url: string; auth: string | null }[] = [];
    const r = await new Trading212Connector(log, fakeT212(calls)).sync(
      conn,
      new Date('2026-11-01T12:00:00Z'),
    );
    expect(r.accounts).toEqual([
      {
        externalId: 't212-42',
        name: 'Example ISA',
        kind: 'isa-stocks',
        currency: 'GBP',
        balance: { minor: 1_234_568, asOf: '2026-11-01' },
      },
    ]);
    expect(r.transactions.map((t) => [t.externalId, t.minor, t.bookedOn])).toEqual([
      ['r1', 100_000, '2026-10-10'],
      ['r2', -5_000, '2026-09-01'],
    ]);
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
    expect(calls[0]!.auth).toBe(`Basic ${Buffer.from('id:sec').toString('base64')}`);
  });

  it('turns a rate limit into a readable error carrying Retry-After', async () => {
    const tooMany = () =>
      Promise.resolve(new Response('slow down', { status: 429, headers: { 'retry-after': '50' } }));
    await expect(
      new Trading212Connector(log, tooMany).sync(conn, new Date()),
    ).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 50,
      message: expect.stringContaining('Trading 212 answered HTTP 429') as unknown,
    });
  });

  it('rejects missing credentials before calling anything', async () => {
    const calls: { method: string; url: string; auth: string | null }[] = [];
    await expect(
      new Trading212Connector(log, fakeT212(calls)).sync({ ...conn, secrets: {} }, new Date()),
    ).rejects.toThrow();
    expect(calls).toEqual([]);
  });
});

describe('read-only enforcement', () => {
  it('only ever allows GETs for Trading 212', () => {
    const methods: string[] = TRADING212_ALLOWED.map((a) => a.method);
    expect(new Set(methods)).toEqual(new Set(['GET']));
  });

  it('refuses an order or any unlisted path before a request leaves the process', async () => {
    let called = 0;
    const http = new ReadOnlyHttp({
      provider: 'Trading 212',
      allowed: TRADING212_ALLOWED,
      log,
      fetch: () => {
        called++;
        return Promise.resolve(new Response('{}'));
      },
    });
    await expect(
      http.json('POST', 'https://live.trading212.com/api/v0/equity/orders/market'),
    ).rejects.toBeInstanceOf(ReadOnlyViolation);
    await expect(
      http.json('GET', 'https://live.trading212.com/api/v0/equity/orders'),
    ).rejects.toBeInstanceOf(ReadOnlyViolation);
    await expect(
      http.json('GET', 'https://evil.example.com/api/v0/equity/account/summary'),
    ).rejects.toBeInstanceOf(ReadOnlyViolation);
    expect(called).toBe(0);
  });

  it('reports an unreachable provider as such', async () => {
    const http = new ReadOnlyHttp({
      provider: 'Trading 212',
      allowed: TRADING212_ALLOWED,
      log,
      fetch: () => Promise.reject(new Error('ECONNRESET')),
    });
    await expect(
      http.json('GET', 'https://live.trading212.com/api/v0/equity/account/summary'),
    ).rejects.toThrow('Trading 212 could not be reached: ECONNRESET');
  });
});

describe('connecting Trading 212', () => {
  it('stores the key encrypted, never returns it, and starts a first sync', async () => {
    const calls: { method: string; url: string; auth: string | null }[] = [];
    const t = testApp({ connectors: [new Trading212Connector(log, fakeT212(calls))] });
    const res = await t.call('POST', '/api/connections/trading212', {
      keyId: 'id',
      secret: 'sec',
      accountName: 'Example ISA',
    });
    expect(res.status).toBe(201);
    await new Promise((r) => setTimeout(r, 20));
    expect(calls.length).toBeGreaterThan(0);
    const conns = await t.call('GET', '/api/connections');
    expect(JSON.stringify(conns.body)).not.toContain('sec');
    expect(conns.body).toMatchObject([
      { kind: 'trading212', label: 'Example ISA', lastRun: { outcome: 'ok' } },
    ]);
  });
});
