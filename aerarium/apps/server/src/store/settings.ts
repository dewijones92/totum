import type { Db } from './db.ts';

export class SettingsStore {
  constructor(private readonly db: Db) {}

  get(key: string): unknown {
    const row = this.db.prepare('SELECT value_json FROM settings WHERE key = ?').get(key) as
      { value_json: string } | undefined;
    return row ? (JSON.parse(row.value_json) as unknown) : undefined;
  }

  set(key: string, value: unknown): void {
    this.db
      .prepare(
        `INSERT INTO settings (key, value_json) VALUES (?, ?)
         ON CONFLICT (key) DO UPDATE SET value_json = excluded.value_json`,
      )
      .run(key, JSON.stringify(value));
  }

  delete(key: string): void {
    this.db.prepare('DELETE FROM settings WHERE key = ?').run(key);
  }

  keysWithPrefix(prefix: string): string[] {
    return (
      this.db.prepare('SELECT key FROM settings WHERE key LIKE ?').all(`${prefix}%`) as unknown as {
        key: string;
      }[]
    ).map((r) => r.key);
  }
}
