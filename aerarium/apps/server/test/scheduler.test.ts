import { describe, expect, it } from 'vitest';
import { testApp } from './helpers.ts';

const HOUR = 3_600_000;

describe('scheduler', () => {
  it('syncs a new connection at once, then only when due', async () => {
    const t = testApp({ fakeProviders: true });
    const id = ((await t.call('POST', '/api/dev/fake/connect')).body as { id: string }).id;
    await t.scheduler.tick();
    t.advance(5 * HOUR);
    await t.scheduler.tick();
    expect(t.connections.runs(id, 10)).toHaveLength(1);
    t.advance(1 * HOUR);
    await t.scheduler.tick();
    expect(t.connections.runs(id, 10)).toHaveLength(2);
  });

  it('retries a failure after the connector’s retry gap, not every tick', async () => {
    const t = testApp({ fakeProviders: true });
    const id = ((await t.call('POST', '/api/dev/fake/connect')).body as { id: string }).id;
    t.fake!.failWith = 'boom';
    await t.scheduler.tick();
    t.advance(30 * 60_000);
    await t.scheduler.tick();
    expect(t.connections.runs(id, 10)).toHaveLength(1);
    t.advance(30 * 60_000);
    await t.scheduler.tick();
    expect(t.connections.runs(id, 10).map((r) => r.outcome)).toEqual(['failed', 'failed']);
  });

  it('marks runs left running by a crash as failed', () => {
    const t = testApp({ fakeProviders: true });
    const id = t.connections.create({ kind: 'fake', label: 'x' }, '2026-11-01T00:00:00Z');
    t.connections.startRun(id, '2026-11-01T00:00:00Z');
    expect(t.connections.abandonRunning('2026-11-01T01:00:00Z')).toBe(1);
    expect(t.connections.lastRun(id)).toMatchObject({
      outcome: 'failed',
      error: 'interrupted by a restart',
    });
  });

  it('skips a connection kind with no connector', async () => {
    const t = testApp();
    const id = t.connections.create({ kind: 'monzo', label: 'x' }, '2026-11-01T00:00:00Z');
    await t.scheduler.tick();
    expect(t.connections.runs(id, 10)).toEqual([]);
    expect((await t.call('POST', `/api/connections/${id}/sync`)).status).toBe(409);
  });
});
