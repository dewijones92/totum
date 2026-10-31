import { describe, expect, it } from 'vitest';
import {
  Healthz,
  judgeConnection,
  judgeManual,
  judgeScheduler,
  worst,
  type SyncRun,
} from '../src/index.ts';

const now = new Date('2026-11-01T12:00:00Z');
const hoursAgo = (h: number) => new Date(now.getTime() - h * 3_600_000).toISOString();
const run = (outcome: SyncRun['outcome'], error: string | null = null): SyncRun => ({
  id: 1,
  connectionId: 'c',
  startedAt: hoursAgo(1),
  finishedAt: outcome === 'running' ? null : hoursAgo(1),
  outcome,
  accountsSeen: 1,
  transactionsAdded: 0,
  error,
});
const base = {
  lastRun: run('ok'),
  lastOkAt: hoursAgo(1),
  consentExpiresAt: null,
  createdAt: hoursAgo(100),
  everyHours: 6,
};

describe('judgeConnection', () => {
  it('is green after a recent good run', () => {
    expect(judgeConnection(base, now)).toEqual({ state: 'ok', reasons: [{ code: 'healthy' }] });
  });
  it('is amber when the last run failed but a good one is recent', () => {
    const r = judgeConnection({ ...base, lastRun: run('failed', 'HTTP 503') }, now);
    expect(r.state).toBe('warn');
    expect(r.reasons).toEqual([{ code: 'last-run-failed', error: 'HTTP 503' }]);
  });
  it('is red when it keeps failing past twice its interval', () => {
    const r = judgeConnection(
      { ...base, lastRun: run('failed', 'HTTP 401'), lastOkAt: hoursAgo(13) },
      now,
    );
    expect(r.state).toBe('fail');
  });
  it('is red when nothing has succeeded for too long, even without a failure', () => {
    const r = judgeConnection({ ...base, lastOkAt: hoursAgo(26) }, now);
    expect(r).toEqual({ state: 'fail', reasons: [{ code: 'stale', hours: 26 }] });
  });
  it('is red once consent has expired and amber in its last week', () => {
    expect(judgeConnection({ ...base, consentExpiresAt: hoursAgo(1) }, now).state).toBe('fail');
    const soon = judgeConnection(
      { ...base, consentExpiresAt: new Date(now.getTime() + 3 * 86_400_000).toISOString() },
      now,
    );
    expect(soon).toEqual({ state: 'warn', reasons: [{ code: 'consent-expiring', days: 3 }] });
  });
  it('waits amber for a first sync, then turns red', () => {
    const fresh = { ...base, lastRun: null, lastOkAt: null, createdAt: hoursAgo(1) };
    expect(judgeConnection(fresh, now).state).toBe('warn');
    expect(judgeConnection({ ...fresh, createdAt: hoursAgo(20) }, now).state).toBe('fail');
  });
  it('says when a run is in progress without changing the colour', () => {
    const r = judgeConnection({ ...base, lastRun: run('running') }, now);
    expect(r.state).toBe('ok');
    expect(r.reasons).toEqual([{ code: 'running' }]);
  });
});

describe('judgeManual', () => {
  it('is amber when the balance was never entered or is older than the limit', () => {
    expect(judgeManual(null, now, 90).state).toBe('warn');
    expect(judgeManual('2026-07-01', now, 90)).toEqual({
      state: 'warn',
      reasons: [{ code: 'manual-stale', days: 123 }],
    });
    expect(judgeManual('2026-10-01', now, 90).state).toBe('ok');
  });
});

describe('judgeScheduler and worst', () => {
  it('turns red when the scheduler has gone quiet', () => {
    expect(judgeScheduler(hoursAgo(0.01), now).state).toBe('ok');
    expect(judgeScheduler(hoursAgo(1), now).state).toBe('fail');
    expect(judgeScheduler(null, now).reasons).toEqual([{ code: 'scheduler-silent', minutes: -1 }]);
  });
  it('takes the worst state', () => {
    expect(worst(['ok', 'warn'])).toBe('warn');
    expect(worst(['warn', 'fail', 'ok'])).toBe('fail');
    expect(worst([])).toBe('ok');
  });
  it('parses a healthz body', () => {
    expect(Healthz.parse({ ok: true, version: 'dev', schedulerLastTickAt: null }).ok).toBe(true);
  });
});
