import { randomUUID } from 'node:crypto';
import type { Db } from './db.ts';

export interface NewTransaction {
  accountId: string;
  externalId: string;
  bookedOn: string;
  minor: number;
  currency: string;
  description: string;
  counterparty: string | null;
}

export interface StoredTransaction extends NewTransaction {
  id: string;
}

export class TransactionsStore {
  constructor(private readonly db: Db) {}

  insertNew(rows: readonly NewTransaction[], now: string): number {
    const stmt = this.db.prepare(
      `INSERT INTO transactions (id, account_id, external_id, booked_on, minor, currency, description, counterparty, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (account_id, external_id) DO NOTHING`,
    );
    let added = 0;
    for (const t of rows) {
      const r = stmt.run(
        randomUUID(),
        t.accountId,
        t.externalId,
        t.bookedOn,
        t.minor,
        t.currency,
        t.description,
        t.counterparty,
        now,
      );
      added += Number(r.changes);
    }
    return added;
  }

  list(filter: { accountId?: string; since?: string; limit: number }): StoredTransaction[] {
    const where: string[] = [];
    const values: (string | number)[] = [];
    if (filter.accountId) {
      where.push('account_id = ?');
      values.push(filter.accountId);
    }
    if (filter.since) {
      where.push('booked_on >= ?');
      values.push(filter.since);
    }
    const sql = `SELECT id, account_id AS accountId, external_id AS externalId, booked_on AS bookedOn,
      minor, currency, description, counterparty FROM transactions
      ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY booked_on DESC, created_at DESC LIMIT ?`;
    return this.db.prepare(sql).all(...values, filter.limit) as unknown as StoredTransaction[];
  }
}
