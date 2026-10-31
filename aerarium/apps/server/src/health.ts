import { statSync } from 'node:fs';
import {
  judgeConnection,
  judgeManual,
  judgeScheduler,
  worst,
  type HealthReport,
  type HealthState,
} from '@aerarium/shared';
import type { AccountsStore } from './store/accounts.ts';
import type { ConnectionsStore } from './store/connections.ts';
import type { SyncEngine } from './sync/engine.ts';

export interface HealthDeps {
  accounts: AccountsStore;
  connections: ConnectionsStore;
  engine: SyncEngine;
  version: string;
  schedulerLastTickAt: () => string | null;
  databaseFile: string | null;
  manualStaleDays: number;
}

export function buildHealthReport(d: HealthDeps, now: Date): HealthReport {
  const sources = d.connections.synced().map((c) => {
    const connector = d.engine.connectorFor(c);
    const lastRun = d.connections.lastRun(c.id);
    const lastOkAt = d.connections.lastOkAt(c.id);
    const judged = judgeConnection(
      {
        lastRun,
        lastOkAt,
        consentExpiresAt: c.consentExpiresAt,
        createdAt: c.createdAt,
        everyHours: connector?.everyHours ?? 24,
      },
      now,
    );
    return {
      connectionId: c.id,
      label: c.label,
      kind: c.kind,
      ...judged,
      lastOkAt,
      consentExpiresAt: c.consentExpiresAt,
      lastRun,
    };
  });
  const manual = d.accounts
    .list()
    .filter((a) => a.sourceKind === 'manual')
    .map((a) => ({
      accountId: a.id,
      name: a.name,
      asOf: a.balanceAsOf,
      ...judgeManual(a.balanceAsOf, now, d.manualStaleDays),
    }));
  const scheduler = judgeScheduler(d.schedulerLastTickAt(), now);
  let databaseBytes = 0;
  if (d.databaseFile) {
    try {
      databaseBytes = statSync(d.databaseFile).size;
    } catch {
      databaseBytes = 0;
    }
  }
  const app = {
    ...scheduler,
    version: d.version,
    schedulerLastTickAt: d.schedulerLastTickAt(),
    databaseBytes,
  };
  const states: HealthState[] = [
    ...sources.map((s) => s.state),
    ...manual.map((m) => m.state),
    app.state,
  ];
  return { overall: worst(states), sources, manual, app };
}
