import { mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { openDb } from '../src/store/db.ts';
import { Snapshots } from '../src/store/snapshot.ts';

describe('Snapshots', () => {
  it('writes one readable copy a night after the hour, and keeps the last three', () => {
    const dir = mkdtempSync(join(tmpdir(), 'aerarium-snap-'));
    const db = openDb(join(dir, 'data'));
    db.prepare("INSERT INTO settings (key, value_json) VALUES ('probe', '1')").run();
    const snaps = new Snapshots({
      db,
      dir: join(dir, 'snapshots'),
      log: pino({ level: 'silent' }),
    });
    expect(snaps.maybeTake(new Date('2026-11-01T22:59:00Z'))).toBeNull();
    const first = snaps.maybeTake(new Date('2026-11-01T23:00:00Z'));
    expect(first).not.toBeNull();
    expect(snaps.maybeTake(new Date('2026-11-01T23:30:00Z'))).toBeNull();
    const copy = new DatabaseSync(first!, { readOnly: true });
    expect(copy.prepare("SELECT value_json FROM settings WHERE key = 'probe'").get()).toEqual({
      value_json: '1',
    });
    copy.close();
    for (const d of ['02', '03', '04']) snaps.maybeTake(new Date(`2026-11-${d}T23:10:00Z`));
    expect(readdirSync(join(dir, 'snapshots')).sort()).toEqual([
      'aerarium-2026-11-02.sqlite',
      'aerarium-2026-11-03.sqlite',
      'aerarium-2026-11-04.sqlite',
    ]);
    expect(snaps.latestDay()).toBe('2026-11-04');
  });
});

describe('health and snapshots', () => {
  it('turns the app amber when the nightly copy is over two days old, not on a fresh install', async () => {
    const { testApp } = await import('./helpers.ts');
    const dir = mkdtempSync(join(tmpdir(), 'aerarium-snaph-'));
    const t = testApp({ snapshotsDir: dir });
    await t.scheduler.tick();
    expect(t.healthReport().app.state).toBe('ok');
    t.advance(11.5 * 3_600_000);
    await t.scheduler.tick();
    expect(t.healthReport().app).toMatchObject({ state: 'ok', lastSnapshotDay: '2026-11-01' });
    t.advance(71 * 3_600_000);
    await t.scheduler.tick();
    expect(t.healthReport().app).toMatchObject({
      state: 'warn',
      reasons: [{ code: 'snapshot-stale', lastDay: '2026-11-01' }],
    });
  });
});
