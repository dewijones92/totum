import { transaction, type Db } from '../store/db.ts';
import type { AccountsStore } from '../store/accounts.ts';
import type { ConnectionRecord, ConnectionsStore } from '../store/connections.ts';
import type { TransactionsStore } from '../store/transactions.ts';
import type { Connector } from '../connectors/types.ts';
import type { Logger } from '../log.ts';
import { minor } from '@aerarium/shared';

export interface SyncEngineDeps {
  db: Db;
  accounts: AccountsStore;
  connections: ConnectionsStore;
  transactions: TransactionsStore;
  connectors: ReadonlyMap<string, Connector>;
  log: Logger;
  clock: () => Date;
  onRunFinished?: (connectionId: string) => void;
}

export type RunOutcome =
  | { kind: 'ok'; accountsSeen: number; transactionsAdded: number }
  | { kind: 'failed'; error: string }
  | { kind: 'busy' }
  | { kind: 'no-connector' };

const describe = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

export class SyncEngine {
  private readonly inFlight = new Set<string>();

  constructor(private readonly deps: SyncEngineDeps) {}

  isRunning(connectionId: string): boolean {
    return this.inFlight.has(connectionId);
  }

  connectorFor(c: ConnectionRecord): Connector | undefined {
    return this.deps.connectors.get(c.kind);
  }

  async run(connection: ConnectionRecord, reason: string): Promise<RunOutcome> {
    const { log, connections, clock } = this.deps;
    const connector = this.connectorFor(connection);
    if (!connector) {
      log.warn(
        { connection: connection.id, kind: connection.kind, reason },
        'dewidebug sync skipped: no connector for this kind',
      );
      return { kind: 'no-connector' };
    }
    if (this.inFlight.has(connection.id)) {
      log.info({ connection: connection.id, reason }, 'dewidebug sync skipped: already running');
      return { kind: 'busy' };
    }
    this.inFlight.add(connection.id);
    const runId = connections.startRun(connection.id, clock().toISOString());
    const started = performance.now();
    log.info(
      { connection: connection.id, kind: connection.kind, label: connection.label, runId, reason },
      'dewidebug sync started',
    );
    try {
      const result = await connector.sync(connection, clock());
      const now = clock().toISOString();
      const added = transaction(this.deps.db, () => this.store(connection, result, now));
      connections.finishRun(
        runId,
        {
          outcome: 'ok',
          accountsSeen: result.accounts.length,
          transactionsAdded: added,
          error: null,
        },
        now,
      );
      log.info(
        {
          connection: connection.id,
          runId,
          accounts: result.accounts.length,
          transactionsFetched: result.transactions.length,
          transactionsAdded: added,
          consentExpiresAt: result.consentExpiresAt ?? connection.consentExpiresAt,
          ms: Math.round(performance.now() - started),
        },
        'dewidebug sync finished',
      );
      return { kind: 'ok', accountsSeen: result.accounts.length, transactionsAdded: added };
    } catch (error) {
      const message = describe(error);
      connections.finishRun(
        runId,
        { outcome: 'failed', accountsSeen: 0, transactionsAdded: 0, error: message },
        clock().toISOString(),
      );
      log.error(
        {
          connection: connection.id,
          runId,
          err: error,
          ms: Math.round(performance.now() - started),
        },
        'dewidebug sync failed',
      );
      return { kind: 'failed', error: message };
    } finally {
      this.inFlight.delete(connection.id);
      this.deps.onRunFinished?.(connection.id);
    }
  }

  private store(
    connection: ConnectionRecord,
    result: Awaited<ReturnType<Connector['sync']>>,
    now: string,
  ): number {
    const { accounts, connections, transactions } = this.deps;
    const ids = new Map<string, string>();
    for (const a of result.accounts) {
      const id = accounts.upsert(
        {
          connectionId: connection.id,
          externalId: a.externalId,
          name: a.name,
          kind: a.kind,
          currency: a.currency,
        },
        now,
      );
      ids.set(a.externalId, id);
      if (a.balance) {
        accounts.recordBalance(id, a.balance.asOf, minor(a.balance.minor), a.currency, now);
      }
    }
    const rows = result.transactions.flatMap((t) => {
      const accountId =
        ids.get(t.accountExternalId) ?? accounts.idFor(connection.id, t.accountExternalId);
      if (!accountId) return [];
      return [
        {
          accountId,
          externalId: t.externalId,
          bookedOn: t.bookedOn,
          minor: t.minor,
          currency: t.currency,
          description: t.description,
          counterparty: t.counterparty,
        },
      ];
    });
    const added = transactions.insertNew(rows, now);
    if (result.consentExpiresAt !== undefined || result.secrets !== undefined) {
      connections.update(connection.id, {
        ...(result.consentExpiresAt !== undefined
          ? { consentExpiresAt: result.consentExpiresAt }
          : {}),
        ...(result.secrets !== undefined
          ? { secrets: { ...connection.secrets, ...result.secrets } }
          : {}),
      });
    }
    return added;
  }
}
