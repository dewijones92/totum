import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const MIGRATIONS: readonly string[] = [
  `
  CREATE TABLE connections (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    label TEXT NOT NULL,
    created_at TEXT NOT NULL,
    consent_expires_at TEXT,
    config_json TEXT NOT NULL DEFAULT '{}',
    secrets_json TEXT NOT NULL DEFAULT ''
  );
  CREATE TABLE accounts (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    owner TEXT NOT NULL DEFAULT 'mine',
    currency TEXT NOT NULL,
    include_in_net_worth INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    UNIQUE (connection_id, external_id)
  );
  CREATE TABLE balances (
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    as_of TEXT NOT NULL,
    minor INTEGER NOT NULL,
    currency TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    PRIMARY KEY (account_id, as_of)
  );
  CREATE TABLE transactions (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,
    booked_on TEXT NOT NULL,
    minor INTEGER NOT NULL,
    currency TEXT NOT NULL,
    description TEXT NOT NULL,
    counterparty TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (account_id, external_id)
  );
  CREATE INDEX transactions_by_date ON transactions (booked_on DESC);
  CREATE TABLE sync_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    outcome TEXT NOT NULL,
    accounts_seen INTEGER NOT NULL DEFAULT 0,
    transactions_added INTEGER NOT NULL DEFAULT 0,
    error TEXT
  );
  CREATE INDEX sync_runs_by_connection ON sync_runs (connection_id, id DESC);
  CREATE TABLE net_worth_days (
    day TEXT PRIMARY KEY,
    household INTEGER NOT NULL,
    yours INTEGER NOT NULL,
    recorded_at TEXT NOT NULL
  );
  CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value_json TEXT NOT NULL
  );
  INSERT INTO connections (id, kind, label, created_at) VALUES ('manual', 'manual', 'Typed in by you', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
  `,
  `
  ALTER TABLE accounts ADD COLUMN owner_confirmed INTEGER NOT NULL DEFAULT 1;
  `,
];

export type Db = DatabaseSync;

export function openDb(dataDir: string): Db {
  mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(join(dataDir, 'aerarium.sqlite'));
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  migrate(db);
  return db;
}

export function openMemoryDb(): Db {
  const db = new DatabaseSync(':memory:');
  db.exec('PRAGMA foreign_keys = ON;');
  migrate(db);
  return db;
}

function migrate(db: Db): void {
  const row = db.prepare('PRAGMA user_version').get() as { user_version: number } | undefined;
  const current = row?.user_version ?? 0;
  for (let v = current; v < MIGRATIONS.length; v++) {
    db.exec('BEGIN');
    try {
      db.exec(MIGRATIONS[v] ?? '');
      db.exec(`PRAGMA user_version = ${String(v + 1)}`);
      db.exec('COMMIT');
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  }
}

export function transaction<T>(db: Db, work: () => T): T {
  db.exec('BEGIN');
  try {
    const result = work();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}
