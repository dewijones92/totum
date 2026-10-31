import type { SecretBox } from '../secret-box.ts';
import type { Db } from './db.ts';

export class SettingsStore {
  constructor(
    private readonly db: Db,
    private readonly box?: SecretBox,
  ) {}

  setSecret(key: string, value: unknown): void {
    if (!this.box) throw new Error('No secret box to seal settings with');
    this.set(key, { sealed: this.box.seal(JSON.stringify(value)) });
  }

  getSecret(key: string): unknown {
    const v = this.get(key);
    if (!this.box || typeof v !== 'object' || v === null || !('sealed' in v)) return undefined;
    const sealed = v.sealed;
    return typeof sealed === 'string' ? (JSON.parse(this.box.open(sealed)) as unknown) : undefined;
  }

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
