import { createVerify, generateKeyPairSync } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import {
  ENABLE_BANKING_ALLOWED,
  EnableBankingClient,
  EnableBankingConnector,
  kindFromCashAccountType,
  signJwt,
} from '../src/connectors/enable-banking.ts';
import { EnableBankingFlow } from '../src/consent/enable-banking-flow.ts';
import { SecretBox } from '../src/secret-box.ts';
import { ConnectionsStore, type ConnectionRecord } from '../src/store/connections.ts';
import { openMemoryDb } from '../src/store/db.ts';
import { SettingsStore } from '../src/store/settings.ts';
import { testApp } from './helpers.ts';

const log = pino({ level: 'silent' });
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const settings = {
  applicationId: 'app-123',
  privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
  redirectUrl: 'https://aerarium.example.com/api/eb/callback',
};
const U1 = '11111111-1111-1111-1111-111111111111';
const U2 = '22222222-2222-2222-2222-222222222222';
const SESSION = '33333333-3333-3333-3333-333333333333';
const R1 = '44444444-4444-4444-4444-444444444444';
const R2 = '55555555-5555-5555-5555-555555555555';
const now = new Date('2026-11-01T12:00:00Z');

interface Call {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
  auth: string;
}

function fakeEb(calls: Call[], opts: { maxConsent?: number } = {}) {
  let sessions = 0;
  return (url: string, init: RequestInit) => {
    const u = new URL(url);
    const headers = init.headers as Record<string, string>;
    calls.push({
      method: init.method ?? 'GET',
      path: u.pathname,
      query: u.searchParams,
      body: typeof init.body === 'string' ? (JSON.parse(init.body) as unknown) : null,
      auth: headers['authorization'] ?? '',
    });
    const ok = (b: unknown) => Promise.resolve(new Response(JSON.stringify(b), { status: 200 }));
    if (u.pathname === '/aspsps') {
      return ok({
        aspsps: [
          {
            name: 'Example Bank',
            country: 'GB',
            maximum_consent_validity: opts.maxConsent ?? 15_552_000,
          },
        ],
      });
    }
    if (u.pathname === '/auth')
      return ok({ url: 'https://bank.example.com/consent', authorization_id: 'a' });
    if (u.pathname === '/sessions') {
      sessions++;
      const renewed = sessions > 1;
      return ok({
        session_id: SESSION,
        aspsp: { name: 'Example Bank', country: 'GB' },
        access: { valid_until: '2027-01-30T12:00:00Z' },
        accounts: [
          {
            uid: renewed ? R1 : U1,
            identification_hash: 'hash-current',
            name: 'Joint current',
            currency: 'GBP',
            cash_account_type: 'CACC',
          },
          {
            uid: renewed ? R2 : U2,
            identification_hash: 'hash-card',
            name: null,
            product: 'Example Card',
            currency: 'GBP',
            cash_account_type: 'CARD',
          },
        ],
      });
    }
    if (u.pathname.endsWith('/balances')) {
      return ok({
        balances: [
          {
            balance_amount: { amount: '999.00', currency: 'GBP' },
            balance_type: 'ITAV',
            reference_date: '2026-11-01',
          },
          {
            balance_amount: { amount: '1234.50', currency: 'GBP' },
            balance_type: 'CLBD',
            reference_date: '2026-10-31',
          },
        ],
      });
    }
    if (u.pathname.endsWith('/transactions')) {
      if (u.searchParams.get('continuation_key') === 'page2') {
        return ok({
          transactions: [
            {
              transaction_amount: { amount: '20.00', currency: 'GBP' },
              credit_debit_indicator: 'CRDT',
              status: 'BOOK',
              booking_date: '2026-10-02',
              remittance_information: ['INTEREST PAID'],
            },
          ],
          continuation_key: null,
        });
      }
      return ok({
        transactions: [
          {
            entry_reference: 'e1',
            transaction_amount: { amount: '42.50', currency: 'GBP' },
            credit_debit_indicator: 'DBIT',
            status: 'BOOK',
            booking_date: '2026-10-30',
            remittance_information: ['EXAMPLE ENERGY DD'],
            creditor: { name: 'Example Energy' },
          },
          {
            entry_reference: 'p1',
            transaction_amount: { amount: '5.00', currency: 'GBP' },
            credit_debit_indicator: 'DBIT',
            status: 'PDNG',
            booking_date: '2026-10-31',
          },
        ],
        continuation_key: 'page2',
      });
    }
    return Promise.resolve(new Response('not found', { status: 404 }));
  };
}

const connection = (over: Partial<ConnectionRecord> = {}): ConnectionRecord => ({
  id: 'c',
  kind: 'enable-banking',
  label: 'Example Bank',
  createdAt: now.toISOString(),
  consentExpiresAt: null,
  config: {
    aspspName: 'Example Bank',
    aspspCountry: 'GB',
    accounts: [
      { uid: U1, key: 'hash-current', name: 'Joint current', kind: 'current', currency: 'GBP' },
      { uid: U2, key: 'hash-card', name: 'Example Card', kind: 'credit-card', currency: 'GBP' },
    ],
  },
  secrets: { sessionId: SESSION },
  ...over,
});

describe('signJwt', () => {
  it('signs an RS256 token Enable Banking can verify, valid for an hour', () => {
    const token = signJwt(settings, now);
    const [h, p, sig] = token.split('.');
    const header = JSON.parse(Buffer.from(h!, 'base64url').toString()) as Record<string, unknown>;
    const payload = JSON.parse(Buffer.from(p!, 'base64url').toString()) as Record<
      string,
      number | string
    >;
    expect(header).toEqual({ typ: 'JWT', alg: 'RS256', kid: 'app-123' });
    expect(payload).toMatchObject({ iss: 'enablebanking.com', aud: 'api.enablebanking.com' });
    expect(Number(payload['exp']) - Number(payload['iat'])).toBe(3600);
    const v = createVerify('RSA-SHA256');
    v.update(`${h!}.${p!}`);
    expect(v.verify(publicKey, Buffer.from(sig!, 'base64url'))).toBe(true);
  });
});

describe('EnableBankingConnector', () => {
  it('reads each account, prefers the booked balance, keeps only booked transactions, and pages', async () => {
    const calls: Call[] = [];
    const client = new EnableBankingClient(settings, log, fakeEb(calls));
    const r = await new EnableBankingConnector(client, log).sync(connection(), now);
    expect(r.accounts[0]).toMatchObject({
      externalId: 'hash-current',
      kind: 'current',
      balance: { minor: 123_450, asOf: '2026-10-31' },
    });
    expect(r.accounts[1]).toMatchObject({ externalId: 'hash-card', kind: 'credit-card' });
    const forU1 = r.transactions.filter((t) => t.accountExternalId === 'hash-current');
    expect(
      forU1.map((t) => [t.externalId.slice(0, 5), t.minor, t.description, t.counterparty]),
    ).toEqual([
      ['ref:e', -4_250, 'EXAMPLE ENERGY DD', 'Example Energy'],
      ['hash:', 2_000, 'INTEREST PAID', null],
    ]);
    expect(r.config).toEqual({
      syncedThrough: { 'hash-current': '2026-11-01', 'hash-card': '2026-11-01' },
    });
    const first = calls.find((c) => c.path.endsWith('/transactions'))!;
    expect(first.query.get('date_from')).toBe('2024-11-01');
    expect(calls.every((c) => c.auth.startsWith('Bearer '))).toBe(true);
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
  });

  it('gives the same id to a transaction without a reference on every sync', async () => {
    const client = new EnableBankingClient(settings, log, fakeEb([]));
    const a = await new EnableBankingConnector(client, log).sync(connection(), now);
    const b = await new EnableBankingConnector(client, log).sync(connection(), now);
    expect(a.transactions.map((t) => t.externalId)).toEqual(
      b.transactions.map((t) => t.externalId),
    );
  });

  it('asks only from ten days before the last sync once it has synced', async () => {
    const calls: Call[] = [];
    const client = new EnableBankingClient(settings, log, fakeEb(calls));
    const conn = connection();
    await new EnableBankingConnector(client, log).sync(
      { ...conn, config: { ...conn.config, syncedThrough: { 'hash-current': '2026-10-25' } } },
      now,
    );
    const dates = calls.filter(
      (c) => c.path.endsWith('/transactions') && !c.query.get('continuation_key'),
    );
    expect(dates.map((c) => c.query.get('date_from'))).toEqual(['2026-10-15', '2024-11-01']);
  });

  it('maps account types', () => {
    expect(kindFromCashAccountType('SVGS')).toBe('savings');
    expect(kindFromCashAccountType('LOAN')).toBe('loan');
    expect(kindFromCashAccountType(null)).toBe('current');
  });
});

describe('Enable Banking is read-only', () => {
  it('allows no payment endpoint, and POSTs only for consent and sessions', () => {
    expect(ENABLE_BANKING_ALLOWED.some((a) => /payment/i.test(a.path.source))).toBe(false);
    const posts = ENABLE_BANKING_ALLOWED.filter((a) => a.method === 'POST').map(
      (a) => a.path.source,
    );
    expect(posts).toEqual(['^\\/auth$', '^\\/sessions$']);
  });

  it('refuses a payment call before it leaves the process', async () => {
    const calls: Call[] = [];
    const client = new EnableBankingClient(settings, log, fakeEb(calls));
    await expect(
      client.http.json('POST', 'https://api.enablebanking.com/payments'),
    ).rejects.toThrow(/read-only/);
    expect(calls).toEqual([]);
  });
});

describe('consent flow', () => {
  function flowWith(calls: Call[], maxConsent?: number) {
    const db = openMemoryDb();
    const connections = new ConnectionsStore(db, SecretBox.ephemeral());
    const store = new SettingsStore(db);
    const client = new EnableBankingClient(
      settings,
      log,
      fakeEb(calls, maxConsent === undefined ? {} : { maxConsent }),
    );
    return {
      flow: new EnableBankingFlow({
        client,
        connections,
        settings: store,
        log,
        maxConsentDays: 90,
      }),
      connections,
      store,
    };
  }

  it('asks for the shorter of 90 days and the bank maximum, and remembers the state', async () => {
    const calls: Call[] = [];
    const { flow, store } = flowWith(calls, 30 * 86_400);
    const r = await flow.start({ name: 'Example Bank', country: 'GB' }, now);
    expect(r.url).toBe('https://bank.example.com/consent');
    const auth = calls.find((c) => c.path === '/auth')!.body as Record<string, unknown>;
    expect(auth).toMatchObject({
      access: { valid_until: '2026-12-01T12:00:00.000Z' },
      aspsp: { name: 'Example Bank', country: 'GB' },
      psu_type: 'personal',
      redirect_url: settings.redirectUrl,
    });
    expect(store.keysWithPrefix('eb-state:')).toHaveLength(1);
  });

  it('refuses a callback it did not start, and a declined consent', async () => {
    const calls: Call[] = [];
    const { flow } = flowWith(calls);
    expect(await flow.callback({ code: 'x', state: 'forged' }, now)).toMatchObject({
      kind: 'refused',
    });
    await flow.start({ name: 'Example Bank', country: 'GB' }, now);
    const state = (calls.find((c) => c.path === '/auth')!.body as { state: string }).state;
    expect(await flow.callback({ state, error: 'access_denied' }, now)).toMatchObject({
      kind: 'refused',
      reason: 'access_denied',
    });
    expect(calls.some((c) => c.path === '/sessions')).toBe(false);
  });

  it('creates a connection, then a renewal keeps the same one and its history marker', async () => {
    const calls: Call[] = [];
    const { flow, connections } = flowWith(calls);
    const round = async () => {
      await flow.start({ name: 'Example Bank', country: 'GB' }, now);
      const auths = calls.filter((c) => c.path === '/auth');
      const state = (auths[auths.length - 1]!.body as { state: string }).state;
      return flow.callback({ code: 'code', state }, now);
    };
    const first = await round();
    expect(first).toMatchObject({ kind: 'connected', renewed: false, accounts: 2 });
    const id = (first as { connectionId: string }).connectionId;
    const c = connections.get(id)!;
    expect(c.consentExpiresAt).toBe('2027-01-30T12:00:00Z');
    expect(c.secrets).toEqual({ sessionId: SESSION });
    connections.update(id, {
      config: { ...c.config, syncedThrough: { 'hash-current': '2026-10-20' } },
    });
    const second = await round();
    expect(second).toMatchObject({ kind: 'connected', renewed: true, connectionId: id });
    expect(connections.get(id)!.config).toMatchObject({
      syncedThrough: { 'hash-current': '2026-10-20' },
    });
    expect(connections.synced()).toHaveLength(1);
  });
});

describe('Open Banking routes', () => {
  it('say when Open Banking is not set up', async () => {
    const t = testApp();
    expect((await t.call('GET', '/api/eb/status')).body).toEqual({ configured: false });
    expect((await t.call('GET', '/api/eb/banks')).status).toBe(409);
  });

  it('send the browser back to Connections with the outcome', async () => {
    const calls: Call[] = [];
    const t = testApp({
      enableBanking: {
        client: new EnableBankingClient(settings, log, fakeEb(calls)),
        maxConsentDays: 90,
      },
    });
    expect((await t.call('GET', '/api/eb/banks')).body).toEqual([
      { name: 'Example Bank', country: 'GB', maxConsentDays: 180 },
    ]);
    const start = await t.call('POST', '/api/eb/start', { name: 'Example Bank', country: 'GB' });
    expect(start.body).toEqual({ url: 'https://bank.example.com/consent' });
    const state = (calls.find((c) => c.path === '/auth')!.body as { state: string }).state;
    const res = await t.app.request(`/api/eb/callback?code=c&state=${state}`);
    expect(res.status).toBe(302);
    expect(res.headers.get('location')).toBe('/connections?connected=Example%20Bank&renewed=0');
    const bad = await t.app.request('/api/eb/callback?code=c&state=nope');
    expect(bad.headers.get('location')).toMatch(/^\/connections\?failed=/);
  });
});

describe('renewing consent', () => {
  it('keeps the same accounts and does not duplicate history when the bank issues new account uids', async () => {
    const calls: Call[] = [];
    const t = testApp({
      enableBanking: {
        client: new EnableBankingClient(settings, log, fakeEb(calls)),
        maxConsentDays: 90,
      },
    });
    const connect = async () => {
      await t.call('POST', '/api/eb/start', { name: 'Example Bank', country: 'GB' });
      const auths = calls.filter((c) => c.path === '/auth');
      const state = (auths[auths.length - 1]!.body as { state: string }).state;
      await t.app.request(`/api/eb/callback?code=c&state=${state}`);
      await new Promise((r) => setTimeout(r, 20));
    };
    await connect();
    const accountsBefore = t.accounts
      .list()
      .map((a) => a.id)
      .sort();
    const txBefore = (await t.call('GET', '/api/transactions')).body as unknown[];
    expect(accountsBefore).toHaveLength(2);
    expect(txBefore.length).toBeGreaterThan(0);

    await connect();
    const conn = t.connections.synced()[0]!;
    await t.engine.run(conn, 'after renewal');
    expect(
      t.accounts
        .list()
        .map((a) => a.id)
        .sort(),
    ).toEqual(accountsBefore);
    expect(((await t.call('GET', '/api/transactions')).body as unknown[]).length).toBe(
      txBefore.length,
    );
    const afterRenewal = calls.filter(
      (c) => c.path === `/accounts/${R1}/transactions` && !c.query.get('continuation_key'),
    );
    expect(afterRenewal.length).toBeGreaterThan(0);
    expect(new Set(afterRenewal.map((c) => c.query.get('date_from')))).toEqual(
      new Set(['2026-10-22']),
    );
  });
});

describe('edge cases the review found', () => {
  it('keeps two identical same-day payments that have no bank reference', async () => {
    const coffee = {
      transaction_amount: { amount: '3.50', currency: 'GBP' },
      credit_debit_indicator: 'DBIT',
      status: 'BOOK',
      booking_date: '2026-10-30',
      remittance_information: ['EXAMPLE CAFE'],
    };
    const fetchTwo = (url: string) => {
      const u = new URL(url);
      const body = u.pathname.endsWith('/balances')
        ? { balances: [] }
        : { transactions: [coffee, coffee], continuation_key: null };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    };
    const client = new EnableBankingClient(settings, log, fetchTwo);
    const conn = connection();
    const r = await new EnableBankingConnector(client, log).sync(
      {
        ...conn,
        config: { ...conn.config, accounts: [(conn.config['accounts'] as unknown[])[0]] },
      },
      now,
    );
    const ids = r.transactions.map((t) => t.externalId);
    expect(ids).toHaveLength(2);
    expect(new Set(ids).size).toBe(2);
  });

  it('warns and bookmarks only what it read when the page limit cuts a sync short', async () => {
    const client = new EnableBankingClient(settings, log, fakeEb([]));
    const r = await new EnableBankingConnector(client, log, 730, 1).sync(connection(), now);
    expect(r.warnings?.[0]).toMatch(/stopped after 1 pages with more to read/);
    expect(r.config).toMatchObject({ syncedThrough: { 'hash-current': '2026-10-30' } });
  });
});
