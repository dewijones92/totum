import { mkdirSync, readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import type { Logger } from '../log.ts';
import type { Db } from './db.ts';

const NAME = /^aerarium-(\d{4}-\d{2}-\d{2})\.sqlite$/;

export class Snapshots {
  constructor(
    private readonly d: { db: Db; dir: string; log: Logger; keep?: number; hour?: number },
  ) {}

  list(): string[] {
    try {
      return readdirSync(this.d.dir)
        .filter((f) => NAME.test(f))
        .sort();
    } catch {
      return [];
    }
  }

  latestDay(): string | null {
    const last = this.list().at(-1);
    return last ? (NAME.exec(last)?.[1] ?? null) : null;
  }

  maybeTake(now: Date): string | null {
    const day = now.toISOString().slice(0, 10);
    if (now.getUTCHours() < (this.d.hour ?? 23) || this.latestDay() === day) return null;
    mkdirSync(this.d.dir, { recursive: true, mode: 0o700 });
    const file = join(this.d.dir, `aerarium-${day}.sqlite`);
    const started = performance.now();
    this.d.db.exec(`VACUUM INTO '${file.replace(/'/g, "''")}'`);
    const old = this.list().slice(0, -(this.d.keep ?? 3));
    for (const f of old) rmSync(join(this.d.dir, f), { force: true });
    this.d.log.info(
      { file, ms: Math.round(performance.now() - started), removed: old },
      'dewidebug nightly snapshot written',
    );
    return file;
  }
}
