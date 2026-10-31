import { randomUUID } from 'node:crypto';
import { SourceKind, SyncRun, SyncOutcome } from '@aerarium/shared';
import type { SecretBox } from '../secret-box.ts';
import type { Db } from './db.ts';

export interface ConnectionRecord {
  id: string;
  kind: SourceKind;
  label: string;
  createdAt: string;
  consentExpiresAt: string | null;
  config: Record<string, unknown>;
  secrets: Record<string, unknown>;
}

interface ConnectionRow {
  id: string;
  kind: string;
  label: string;
  created_at: string;
  consent_expires_at: string | null;
  config_json: string;
  secrets_json: string;
}

interface RunRow {
  id: number;
  connection_id: string;
  started_at: string;
  finished_at: string | null;
  outcome: string;
  accounts_seen: number;
  transactions_added: number;
  error: string | null;
}

const asRecord = (json: string): Record<string, unknown> => {
  const v: unknown = JSON.parse(json);
  return typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};
};

function toConnection(r: ConnectionRow, box: SecretBox): ConnectionRecord {
  return {
    id: r.id,
    kind: SourceKind.parse(r.kind),
    label: r.label,
    createdAt: r.created_at,
    consentExpiresAt: r.consent_expires_at,
    config: asRecord(r.config_json),
    secrets: r.secrets_json === '' ? {} : asRecord(box.open(r.secrets_json)),
  };
}

function toRun(r: RunRow): SyncRun {
  return SyncRun.parse({
    id: r.id,
    connectionId: r.connection_id,
    startedAt: r.started_at,
    finishedAt: r.finished_at,
    outcome: SyncOutcome.parse(r.outcome),
    accountsSeen: r.accounts_seen,
    transactionsAdded: r.transactions_added,
    error: r.error,
  });
}

export class ConnectionsStore {
  constructor(
    private readonly db: Db,
    private readonly box: SecretBox,
  ) {}

  list(): ConnectionRecord[] {
    return (
      this.db
        .prepare('SELECT * FROM connections ORDER BY created_at')
        .all() as unknown as ConnectionRow[]
    ).map((r) => toConnection(r, this.box));
  }

  synced(): ConnectionRecord[] {
    return this.list().filter((c) => c.kind !== 'manual');
  }

  get(id: string): ConnectionRecord | null {
    const row = this.db.prepare('SELECT * FROM connections WHERE id = ?').get(id) as
      ConnectionRow | undefined;
    return row ? toConnection(row, this.box) : null;
  }

  create(
    input: {
      kind: SourceKind;
      label: string;
      config?: Record<string, unknown>;
      secrets?: Record<string, unknown>;
      consentExpiresAt?: string | null;
    },
    now: string,
  ): string {
    const id = randomUUID();
    this.db
      .prepare(
        `INSERT INTO connections (id, kind, label, created_at, consent_expires_at, config_json, secrets_json)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        id,
        input.kind,
        input.label,
        now,
        input.consentExpiresAt ?? null,
        JSON.stringify(input.config ?? {}),
        this.box.seal(JSON.stringify(input.secrets ?? {})),
      );
    return id;
  }

  update(
    id: string,
    p: {
      label?: string;
      consentExpiresAt?: string | null;
      config?: Record<string, unknown>;
      secrets?: Record<string, unknown>;
    },
  ): void {
    const current = this.get(id);
    if (!current) return;
    this.db
      .prepare(
        `UPDATE connections SET label = ?, consent_expires_at = ?, config_json = ?, secrets_json = ?
         WHERE id = ?`,
      )
      .run(
        p.label ?? current.label,
        p.consentExpiresAt === undefined ? current.consentExpiresAt : p.consentExpiresAt,
        JSON.stringify(p.config ?? current.config),
        this.box.seal(JSON.stringify(p.secrets ?? current.secrets)),
        id,
      );
  }

  delete(id: string): void {
    if (id === 'manual') throw new Error('The manual connection cannot be removed');
    this.db.prepare('DELETE FROM connections WHERE id = ?').run(id);
  }

  startRun(connectionId: string, now: string): number {
    const r = this.db
      .prepare(
        `INSERT INTO sync_runs (connection_id, started_at, outcome) VALUES (?, ?, 'running')`,
      )
      .run(connectionId, now);
    return Number(r.lastInsertRowid);
  }

  finishRun(
    id: number,
    p: {
      outcome: 'ok' | 'failed';
      accountsSeen: number;
      transactionsAdded: number;
      error: string | null;
    },
    now: string,
  ): void {
    this.db
      .prepare(
        `UPDATE sync_runs SET finished_at = ?, outcome = ?, accounts_seen = ?, transactions_added = ?, error = ?
         WHERE id = ?`,
      )
      .run(now, p.outcome, p.accountsSeen, p.transactionsAdded, p.error, id);
  }

  abandonRunning(now: string): number {
    const r = this.db
      .prepare(
        `UPDATE sync_runs SET finished_at = ?, outcome = 'failed', error = 'interrupted by a restart'
         WHERE outcome = 'running'`,
      )
      .run(now);
    return Number(r.changes);
  }

  lastRun(connectionId: string): SyncRun | null {
    const row = this.db
      .prepare('SELECT * FROM sync_runs WHERE connection_id = ? ORDER BY id DESC LIMIT 1')
      .get(connectionId) as RunRow | undefined;
    return row ? toRun(row) : null;
  }

  lastOkAt(connectionId: string): string | null {
    const row = this.db
      .prepare(
        `SELECT finished_at FROM sync_runs WHERE connection_id = ? AND outcome = 'ok'
         ORDER BY id DESC LIMIT 1`,
      )
      .get(connectionId) as { finished_at: string } | undefined;
    return row?.finished_at ?? null;
  }

  runs(connectionId: string, limit: number): SyncRun[] {
    return (
      this.db
        .prepare('SELECT * FROM sync_runs WHERE connection_id = ? ORDER BY id DESC LIMIT ?')
        .all(connectionId, limit) as unknown as RunRow[]
    ).map(toRun);
  }
}
