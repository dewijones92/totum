import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { SecretBox } from '../src/secret-box.ts';
import { openMemoryDb } from '../src/store/db.ts';
import { ConnectionsStore } from '../src/store/connections.ts';

describe('SecretBox', () => {
  it('round-trips, and two seals of the same text differ', () => {
    const box = SecretBox.ephemeral();
    const a = box.seal('token');
    expect(box.open(a)).toBe('token');
    expect(box.seal('token')).not.toBe(a);
  });

  it('refuses a tampered value and a different key', () => {
    const box = SecretBox.ephemeral();
    const sealed = box.seal('token');
    const parts = sealed.split(':');
    const data = Buffer.from(parts[3]!, 'base64');
    data[0] = data[0]! ^ 0xff;
    const tampered = [...parts.slice(0, 3), data.toString('base64')].join(':');
    expect(() => box.open(tampered)).toThrow();
    expect(() => SecretBox.ephemeral().open(sealed)).toThrow();
    expect(() => box.open('plain')).toThrow(/format/);
  });

  it('loads a 32-byte key file and rejects a short one', () => {
    const dir = mkdtempSync(join(tmpdir(), 'aerarium-key-'));
    writeFileSync(join(dir, 'good'), randomBytes(32).toString('base64'));
    writeFileSync(join(dir, 'bad'), randomBytes(16).toString('base64'));
    expect(SecretBox.fromKeyFile(join(dir, 'good')).ephemeral).toBe(false);
    expect(() => SecretBox.fromKeyFile(join(dir, 'bad'))).toThrow(/32 bytes/);
  });

  it('stores connection secrets encrypted, never as plain text', () => {
    const db = openMemoryDb();
    const store = new ConnectionsStore(db, SecretBox.ephemeral());
    const id = store.create(
      { kind: 'fake', label: 'x', secrets: { access_token: 'plain-token-value' } },
      '2026-11-01T00:00:00Z',
    );
    const raw = db.prepare('SELECT secrets_json FROM connections WHERE id = ?').get(id) as {
      secrets_json: string;
    };
    expect(raw.secrets_json).toMatch(/^v1:/);
    expect(raw.secrets_json).not.toContain('plain-token-value');
    expect(store.get(id)?.secrets).toEqual({ access_token: 'plain-token-value' });
  });
});
