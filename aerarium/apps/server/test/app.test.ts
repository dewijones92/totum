import { describe, expect, it } from 'vitest';
import type { Account, Dashboard, HealthReport } from '@aerarium/shared';
import { testApp } from './helpers.ts';

const manual = {
  name: 'Example savings',
  kind: 'savings',
  owner: 'mine',
  balanceMinor: 1_000_000,
  asOf: '2026-10-31',
};

describe('guards', () => {
  it('serves /healthz to anyone, with the version', async () => {
    const t = testApp({ ip: '8.8.8.8', ownerEmail: 'me@example.com' });
    const res = await t.app.request('/healthz');
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ ok: true, version: 'test' });
  });

  it('refuses a request from outside the trusted network', async () => {
    const res = await testApp({ ip: '8.8.8.8' }).app.request('/api/accounts');
    expect(res.status).toBe(403);
  });

  it('refuses a signed-in person who is not the owner, and anyone without an email', async () => {
    const t = testApp({ ownerEmail: 'me@example.com' });
    const other = await t.app.request('/api/accounts', {
      headers: { 'x-forwarded-email': 'someone-else@example.com' },
    });
    expect(other.status).toBe(403);
    expect(await other.text()).toContain('private');
    expect((await t.app.request('/api/accounts')).status).toBe(403);
  });

  it('lets the owner through, ignoring case', async () => {
    const res = await testApp({ ownerEmail: 'me@example.com' }).app.request('/api/accounts', {
      headers: { 'x-forwarded-email': 'Me@Example.com' },
    });
    expect(res.status).toBe(200);
  });
});

describe('manual accounts', () => {
  it('adds one with a balance and counts it in net worth', async () => {
    const t = testApp();
    const created = await t.call('POST', '/api/accounts/manual', manual);
    expect(created.status).toBe(201);
    expect(created.body).toMatchObject({ name: 'Example savings', balance: { minor: 1_000_000 } });
    const mortgage = await t.call('POST', '/api/accounts/manual', {
      ...manual,
      name: 'Example mortgage',
      kind: 'mortgage',
      owner: 'joint',
      balanceMinor: 400_000,
    });
    expect(mortgage.status).toBe(201);
    const dash = (await t.call('GET', '/api/dashboard')).body as Dashboard;
    expect(dash.netWorth.household).toBe(600_000);
    expect(dash.netWorth.yours).toBe(800_000);
  });

  it('records a new balance and keeps the history', async () => {
    const t = testApp();
    const { body } = await t.call('POST', '/api/accounts/manual', manual);
    const id = (body as Account).id;
    const updated = await t.call('POST', `/api/accounts/${id}/balance`, {
      balanceMinor: 1_050_000,
      asOf: '2026-11-01',
    });
    expect(updated.body).toMatchObject({
      balance: { minor: 1_050_000 },
      balanceAsOf: '2026-11-01',
    });
    expect((await t.call('GET', `/api/accounts/${id}/history`)).body).toEqual([
      { asOf: '2026-10-31', minor: 1_000_000 },
      { asOf: '2026-11-01', minor: 1_050_000 },
    ]);
  });

  it('changes owner and inclusion, and deletes', async () => {
    const t = testApp();
    const id = ((await t.call('POST', '/api/accounts/manual', manual)).body as Account).id;
    const patched = await t.call('PATCH', `/api/accounts/${id}`, {
      owner: 'joint',
      includeInNetWorth: false,
    });
    expect(patched.body).toMatchObject({ owner: 'joint', includeInNetWorth: false });
    expect((await t.call('DELETE', `/api/accounts/${id}`)).status).toBe(204);
    expect((await t.call('GET', '/api/accounts')).body).toEqual([]);
  });

  it('rejects a bad body with a readable reason', async () => {
    const res = await testApp().call('POST', '/api/accounts/manual', { ...manual, kind: 'piggy' });
    expect(res.status).toBe(400);
    expect(JSON.stringify(res.body)).toContain('kind');
  });

  it('answers 404 for an unknown account', async () => {
    const t = testApp();
    expect((await t.call('PATCH', '/api/accounts/nope', { owner: 'joint' })).status).toBe(404);
    expect((await t.call('GET', '/api/accounts/nope/history')).status).toBe(404);
  });
});

describe('synced connections', () => {
  async function withFake() {
    const t = testApp({ fakeProviders: true });
    const { body } = await t.call('POST', '/api/dev/fake/connect');
    return { t, id: (body as { id: string }).id };
  }

  it('syncs accounts and transactions, records the run, and never exposes secrets', async () => {
    const { t, id } = await withFake();
    t.connections.update(id, { secrets: { access_token: 'shh' } });
    await t.scheduler.tick();
    const accounts = (await t.call('GET', '/api/accounts')).body as Account[];
    expect(accounts.map((a) => a.name)).toContain('Example Card');
    expect(accounts.every((a) => !a.ownerConfirmed)).toBe(true);
    const card = accounts.find((a) => a.name === 'Example Card')!;
    const patched = await t.call('PATCH', `/api/accounts/${card.id}`, { owner: 'joint' });
    expect(patched.body).toMatchObject({ owner: 'joint', ownerConfirmed: true });
    const txs = (await t.call('GET', '/api/transactions')).body as {
      category: string;
      transfer: boolean;
      owner: string;
    }[];
    expect(txs.length).toBe(24);
    expect(txs.filter((x) => x.transfer)).toHaveLength(2);
    expect(txs.some((x) => x.category === 'energy')).toBe(true);
    const out = (await t.call('GET', '/api/outgoings')).body as {
      monthlyMinor: number;
      recurring: { payee: string; priceChange: unknown }[];
    };
    expect(out.monthlyMinor).toBe(16_500);
    expect(out.recurring.map((r) => r.payee)).toEqual(['Example Energy']);
    const conns = (await t.call('GET', '/api/connections')).body;
    expect(conns).toMatchObject([
      { id, lastRun: { outcome: 'ok', accountsSeen: 2, transactionsAdded: 24 } },
    ]);
    expect(JSON.stringify(conns)).not.toContain('shh');
  });

  it('refuses to edit a synced account by hand', async () => {
    const { t } = await withFake();
    await t.scheduler.tick();
    const [a] = (await t.call('GET', '/api/accounts')).body as Account[];
    const res = await t.call('POST', `/api/accounts/${a!.id}/balance`, {
      balanceMinor: 1,
      asOf: '2026-11-01',
    });
    expect(res.status).toBe(409);
  });

  it('turns health red when the source keeps failing, green again when it recovers', async () => {
    const { t, id } = await withFake();
    await t.scheduler.tick();
    let health = (await t.call('GET', '/api/health')).body as HealthReport;
    expect(health.sources[0]!.state).toBe('ok');

    await t.call('POST', '/api/dev/fake/fail', { error: 'HTTP 503 from the bank' });
    t.advance(13 * 3_600_000);
    await t.scheduler.tick();
    health = (await t.call('GET', '/api/health')).body as HealthReport;
    expect(health.overall).toBe('fail');
    expect(health.sources[0]).toMatchObject({
      connectionId: id,
      state: 'fail',
      reasons: [{ code: 'last-run-failed', error: 'HTTP 503 from the bank' }],
    });

    await t.call('POST', '/api/dev/fake/fail', { error: null });
    expect((await t.call('POST', `/api/connections/${id}/sync`)).status).toBe(202);
    await new Promise((r) => setTimeout(r, 10));
    health = (await t.call('GET', '/api/health')).body as HealthReport;
    expect(health.sources[0]!.state).toBe('ok');
    const runs = (await t.call('GET', `/api/connections/${id}/runs`)).body as { outcome: string }[];
    expect(runs.map((r) => r.outcome)).toEqual(['ok', 'failed', 'ok']);
  });

  it('removes a connection with its accounts', async () => {
    const { t, id } = await withFake();
    await t.scheduler.tick();
    expect((await t.call('DELETE', `/api/connections/${id}`)).status).toBe(204);
    expect((await t.call('GET', '/api/accounts')).body).toEqual([]);
    expect((await t.call('DELETE', '/api/connections/manual')).status).toBe(404);
  });
});

describe('health of manual balances and the app', () => {
  it('flags a manual balance older than the limit, and a silent scheduler', async () => {
    const t = testApp();
    await t.call('POST', '/api/accounts/manual', { ...manual, asOf: '2026-06-01' });
    let health = (await t.call('GET', '/api/health')).body as HealthReport;
    expect(health.manual[0]).toMatchObject({ state: 'warn', reasons: [{ code: 'manual-stale' }] });
    expect(health.app.state).toBe('fail');
    await t.scheduler.tick();
    health = (await t.call('GET', '/api/health')).body as HealthReport;
    expect(health.app.state).toBe('ok');
    expect(health.overall).toBe('warn');
  });

  it('snapshots net worth once per day on the scheduler tick', async () => {
    const t = testApp();
    await t.call('POST', '/api/accounts/manual', manual);
    await t.scheduler.tick();
    t.advance(86_400_000);
    await t.scheduler.tick();
    const dash = (await t.call('GET', '/api/dashboard')).body as Dashboard;
    expect(dash.history.map((h) => h.day)).toEqual(['2026-11-01', '2026-11-02']);
  });
});
