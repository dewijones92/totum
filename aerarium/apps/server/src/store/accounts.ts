import { randomUUID } from 'node:crypto';
import {
  Account,
  AccountKind,
  money,
  Owner,
  SourceKind,
  type AccountPatch,
  type IsoDate,
  type Minor,
} from '@aerarium/shared';
import type { Db } from './db.ts';

interface AccountRow {
  id: string;
  connection_id: string;
  source_kind: string;
  name: string;
  kind: string;
  owner: string;
  currency: string;
  include_in_net_worth: number;
  owner_confirmed: number;
  minor: number | null;
  balance_currency: string | null;
  as_of: string | null;
}

const SELECT = `
  SELECT a.id, a.connection_id, c.kind AS source_kind, a.name, a.kind, a.owner, a.currency,
         a.include_in_net_worth, a.owner_confirmed, b.minor, b.currency AS balance_currency, b.as_of
  FROM accounts a
  JOIN connections c ON c.id = a.connection_id
  LEFT JOIN balances b ON b.account_id = a.id
    AND b.as_of = (SELECT MAX(as_of) FROM balances WHERE account_id = a.id)
`;

function toAccount(r: AccountRow): Account {
  return Account.parse({
    id: r.id,
    connectionId: r.connection_id,
    sourceKind: SourceKind.parse(r.source_kind),
    name: r.name,
    kind: AccountKind.parse(r.kind),
    owner: Owner.parse(r.owner),
    currency: r.currency,
    includeInNetWorth: r.include_in_net_worth === 1,
    ownerConfirmed: r.owner_confirmed === 1,
    balance: r.minor === null ? null : money(r.minor, r.balance_currency ?? r.currency),
    balanceAsOf: r.as_of,
  });
}

export interface UpsertAccount {
  connectionId: string;
  externalId: string;
  name: string;
  kind: AccountKind;
  currency: string;
}

export class AccountsStore {
  constructor(private readonly db: Db) {}

  list(): Account[] {
    const rows = this.db
      .prepare(`${SELECT} ORDER BY c.kind, a.kind, a.name`)
      .all() as unknown as AccountRow[];
    return rows.map(toAccount);
  }

  get(id: string): Account | null {
    const row = this.db.prepare(`${SELECT} WHERE a.id = ?`).get(id) as AccountRow | undefined;
    return row ? toAccount(row) : null;
  }

  idFor(connectionId: string, externalId: string): string | null {
    const row = this.db
      .prepare('SELECT id FROM accounts WHERE connection_id = ? AND external_id = ?')
      .get(connectionId, externalId) as { id: string } | undefined;
    return row?.id ?? null;
  }

  upsert(a: UpsertAccount, now: string): string {
    const existing = this.idFor(a.connectionId, a.externalId);
    if (existing) {
      this.db.prepare('UPDATE accounts SET currency = ? WHERE id = ?').run(a.currency, existing);
      return existing;
    }
    const id = randomUUID();
    this.db
      .prepare(
        `INSERT INTO accounts (id, connection_id, external_id, name, kind, currency, created_at, owner_confirmed)
         VALUES (?, ?, ?, ?, ?, ?, ?, 0)`,
      )
      .run(id, a.connectionId, a.externalId, a.name, a.kind, a.currency, now);
    return id;
  }

  createManual(
    input: { name: string; kind: AccountKind; owner: Owner; currency: string },
    now: string,
  ): string {
    const id = randomUUID();
    this.db
      .prepare(
        `INSERT INTO accounts (id, connection_id, external_id, name, kind, owner, currency, created_at)
         VALUES (?, 'manual', ?, ?, ?, ?, ?, ?)`,
      )
      .run(id, id, input.name, input.kind, input.owner, input.currency, now);
    return id;
  }

  patch(id: string, p: AccountPatch): void {
    const sets: string[] = [];
    const values: (string | number)[] = [];
    if (p.name !== undefined) {
      sets.push('name = ?');
      values.push(p.name);
    }
    if (p.kind !== undefined) {
      sets.push('kind = ?');
      values.push(p.kind);
    }
    if (p.owner !== undefined) {
      sets.push('owner = ?', 'owner_confirmed = 1');
      values.push(p.owner);
    }
    if (p.includeInNetWorth !== undefined) {
      sets.push('include_in_net_worth = ?');
      values.push(p.includeInNetWorth ? 1 : 0);
    }
    if (sets.length === 0) return;
    this.db.prepare(`UPDATE accounts SET ${sets.join(', ')} WHERE id = ?`).run(...values, id);
  }

  delete(id: string): void {
    this.db.prepare('DELETE FROM accounts WHERE id = ?').run(id);
  }

  recordBalance(
    accountId: string,
    asOf: IsoDate,
    value: Minor,
    currency: string,
    now: string,
  ): void {
    this.db
      .prepare(
        `INSERT INTO balances (account_id, as_of, minor, currency, recorded_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (account_id, as_of) DO UPDATE SET minor = excluded.minor,
           currency = excluded.currency, recorded_at = excluded.recorded_at`,
      )
      .run(accountId, asOf, value, currency, now);
  }

  balanceOn(accountId: string, day: string): number | null {
    const row = this.db
      .prepare(
        'SELECT minor FROM balances WHERE account_id = ? AND as_of <= ? ORDER BY as_of DESC LIMIT 1',
      )
      .get(accountId, day) as { minor: number } | undefined;
    return row?.minor ?? null;
  }

  balanceHistory(accountId: string): { asOf: string; minor: number }[] {
    return this.db
      .prepare('SELECT as_of AS asOf, minor FROM balances WHERE account_id = ? ORDER BY as_of')
      .all(accountId) as unknown as { asOf: string; minor: number }[];
  }
}
