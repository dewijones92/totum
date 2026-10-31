import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { loadConfig } from '../src/config.ts';
import { ReadOnlyHttp } from '../src/http/read-only.ts';
import { LogOnlyNotifier, type Notifier } from '../src/notify/notifier.ts';
import { testApp } from './helpers.ts';

const log = pino({ level: 'silent' });
const HOUR = 3_600_000;

describe('hardening from the review', () => {
  it('refuses to start in production without an owner', () => {
    expect(() => loadConfig({ DATA_DIR: '/x', NODE_ENV: 'production' })).toThrow(
      /OWNER_EMAIL must be set/,
    );
    expect(
      loadConfig({ DATA_DIR: '/x', NODE_ENV: 'production', OWNER_EMAIL: 'me@example.com' })
        .OWNER_EMAIL,
    ).toBe('me@example.com');
  });

  it('only calls providers over https and never follows a redirect', async () => {
    const seen: RequestInit[] = [];
    const http = new ReadOnlyHttp({
      provider: 'Example',
      allowed: [{ method: 'GET', host: 'api.example.com', path: /^\/x$/ }],
      log,
      fetch: (_url, init) => {
        seen.push(init);
        return Promise.resolve(new Response('{}'));
      },
    });
    expect(http.isAllowed('GET', 'http://api.example.com/x')).toBe(false);
    expect(http.isAllowed('GET', 'https://user:pw@api.example.com/x')).toBe(false);
    await http.json('GET', 'https://api.example.com/x');
    expect(seen[0]?.redirect).toBe('error');
  });

  it('refuses writes that do not come from the app', async () => {
    const t = testApp();
    const body = JSON.stringify({
      name: 'X',
      kind: 'savings',
      owner: 'mine',
      balanceMinor: 1,
      asOf: '2026-11-01',
    });
    const cross = await t.app.request('/api/accounts/manual', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'sec-fetch-site': 'same-site' },
      body,
    });
    expect(cross.status).toBe(403);
    const form = await t.app.request('/api/accounts/manual', {
      method: 'POST',
      headers: { 'content-type': 'text/plain' },
      body,
    });
    expect(form.status).toBe(403);
    const ok = await t.app.request('/api/accounts/manual', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'sec-fetch-site': 'same-origin' },
      body,
    });
    expect(ok.status).toBe(201);
  });

  it('refuses a Monzo sign-in link older than an hour', async () => {
    const t = testApp({ publicUrl: 'https://aerarium.example.com' });
    await t.call('POST', '/api/monzo/credentials', { clientId: 'c', clientSecret: 's' });
    const { url } = (await t.call('POST', '/api/monzo/start')).body as { url: string };
    const state = new URL(url).searchParams.get('state')!;
    t.advance(2 * HOUR);
    const res = await t.app.request(`/api/monzo/callback?code=x&state=${state}`);
    expect(res.headers.get('location')).toMatch(/^\/connections\?failed=/);
  });

  it('still makes last week’s report on Tuesday when Monday was missed, once', async () => {
    const notifier = new LogOnlyNotifier(log);
    const t = testApp({ notifier, publicUrl: 'https://aerarium.example.com' });
    await t.scheduler.tick();
    t.advance(46 * HOUR);
    await t.scheduler.tick();
    await t.scheduler.tick();
    expect(notifier.sent.filter((m) => m.startsWith('📊'))).toEqual([
      expect.stringContaining('week to 2026-11-01'),
    ]);
  });

  it('retries a health alert that Matrix failed to deliver', async () => {
    const sent: string[] = [];
    let up = false;
    const flaky: Notifier = {
      kind: 'matrix',
      send: (text) => {
        if (up) sent.push(text);
        return Promise.resolve(up);
      },
    };
    const t = testApp({
      fakeProviders: true,
      notifier: flaky,
      publicUrl: 'https://aerarium.example.com',
    });
    await t.call('POST', '/api/dev/fake/connect');
    await t.scheduler.tick();
    t.fake!.failWith = 'HTTP 503';
    for (let i = 0; i < 14; i++) {
      t.advance(HOUR);
      await t.scheduler.tick();
    }
    expect(sent).toEqual([]);
    up = true;
    t.advance(60_000);
    await t.scheduler.tick();
    expect(sent.filter((m) => m.includes('broken'))).toHaveLength(1);
  });

  it('alerts a large transaction found by Sync now, not only by the scheduler', async () => {
    const notifier = new LogOnlyNotifier(log);
    const t = testApp({ fakeProviders: true, notifier, publicUrl: 'https://aerarium.example.com' });
    t.advance(4 * 24 * HOUR);
    const { id } = (await t.call('POST', '/api/dev/fake/connect')).body as { id: string };
    await t.call('POST', `/api/connections/${id}/sync`);
    await new Promise((r) => setTimeout(r, 20));
    expect(notifier.sent.filter((m) => m.startsWith('💷'))).toHaveLength(1);
  });
});
