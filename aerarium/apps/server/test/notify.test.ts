import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { LogOnlyNotifier, MatrixNotifier } from '../src/notify/notifier.ts';
import { diffHealth } from '../src/notify/watchers.ts';
import { testApp } from './helpers.ts';

const log = pino({ level: 'silent' });
const HOUR = 3_600_000;

describe('diffHealth', () => {
  const now = (state: 'ok' | 'warn' | 'fail') =>
    new Map([['source:a', { label: 'Example Bank', state, reason: 'HTTP 503' }]]);
  it('speaks only when something breaks or recovers, never for amber wobbles', () => {
    expect(diffHealth({ 'source:a': 'ok' }, now('fail'))).toMatchObject([
      { from: 'ok', to: 'fail' },
    ]);
    expect(diffHealth({ 'source:a': 'fail' }, now('ok'))).toMatchObject([
      { from: 'fail', to: 'ok' },
    ]);
    expect(diffHealth({ 'source:a': 'ok' }, now('warn'))).toEqual([]);
    expect(diffHealth({ 'source:a': 'fail' }, now('fail'))).toEqual([]);
    expect(diffHealth({}, now('fail'))).toMatchObject([{ from: null, to: 'fail' }]);
  });
});

describe('alerts through the scheduler', () => {
  it('sends one message when a source breaks, none while it stays broken, one when it recovers', async () => {
    const notifier = new LogOnlyNotifier(log);
    const t = testApp({ fakeProviders: true, notifier, publicUrl: 'https://aerarium.example.com' });
    const id = ((await t.call('POST', '/api/dev/fake/connect')).body as { id: string }).id;
    await t.scheduler.tick();
    const afterFirst = notifier.sent.length;
    t.fake!.failWith = 'HTTP 503 from the bank';
    for (let i = 0; i < 14; i++) {
      t.advance(HOUR);
      await t.scheduler.tick();
    }
    const broken = notifier.sent.slice(afterFirst).filter((m) => m.includes('broken'));
    expect(broken).toHaveLength(1);
    expect(broken[0]).toContain('Example Bank (fake) is broken (HTTP 503 from the bank)');
    expect(broken[0]).toContain('https://aerarium.example.com/health');
    t.fake!.failWith = null;
    await t.engine.run(t.connections.get(id)!, 'test');
    await t.scheduler.tick();
    expect(notifier.sent.filter((m) => m.includes('working again'))).toHaveLength(1);
  });

  it('flags a large new transaction once, but not old backfill or transfers', async () => {
    const notifier = new LogOnlyNotifier(log);
    const t = testApp({ fakeProviders: true, notifier, publicUrl: 'https://aerarium.example.com' });
    t.advance(4 * 24 * HOUR);
    await t.call('POST', '/api/dev/fake/connect');
    await t.scheduler.tick();
    const large = notifier.sent.filter((m) => m.startsWith('💷'));
    expect(large).toEqual([
      '💷 Aerarium: £3,000.00 into Example Bank current account: EXAMPLE EMPLOYER SALARY (2026-11-01)',
    ]);
    t.advance(7 * HOUR);
    await t.scheduler.tick();
    expect(notifier.sent.filter((m) => m.startsWith('💷'))).toHaveLength(1);
  });
});

describe('MatrixNotifier', () => {
  it('posts the text to the room with the token from the shared credentials file', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'aerarium-matrix-'));
    const creds = join(dir, 'creds.txt');
    writeFileSync(creds, 'user: @bot:example.com\naccess_token: tok123\n');
    const seen: { url: string; init: RequestInit }[] = [];
    const n = new MatrixNotifier({
      homeserver: 'https://matrix.example.com/',
      room: '!room:example.com',
      credsFile: creds,
      log,
      fetch: (url, init) => {
        seen.push({ url, init });
        return Promise.resolve(new Response('{}', { status: 200 }));
      },
    });
    expect(await n.send('hello')).toBe(true);
    expect(seen[0]!.url).toMatch(
      /^https:\/\/matrix\.example\.com\/_matrix\/client\/v3\/rooms\/!room%3Aexample\.com\/send\/m\.room\.message\//,
    );
    expect(seen[0]!.init.method).toBe('PUT');
    expect((seen[0]!.init.headers as Record<string, string>)['authorization']).toBe(
      'Bearer tok123',
    );
    expect(JSON.parse(seen[0]!.init.body as string)).toEqual({ msgtype: 'm.text', body: 'hello' });
  });

  it('gives up quietly and says so when the token cannot be read', async () => {
    const n = new MatrixNotifier({
      homeserver: 'https://m',
      room: 'r',
      credsFile: '/nonexistent',
      log,
    });
    expect(await n.send('x')).toBe(false);
  });
});
