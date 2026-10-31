import type { Context } from 'hono';
import { computeNetWorth } from '@aerarium/shared';
import { createApp } from './app.ts';
import { FakeConnector } from './connectors/fake.ts';
import type { Connector } from './connectors/types.ts';
import type { Logger } from './log.ts';
import { AccountsStore } from './store/accounts.ts';
import type { SecretBox } from './secret-box.ts';
import { ConnectionsStore } from './store/connections.ts';
import type { Db } from './store/db.ts';
import { NetWorthDaysStore } from './store/net-worth-days.ts';
import { SettingsStore } from './store/settings.ts';
import { EnableBankingFlow } from './consent/enable-banking-flow.ts';
import { EnableBankingConnector, type EnableBankingClient } from './connectors/enable-banking.ts';
import { TransactionsStore } from './store/transactions.ts';
import { SyncEngine } from './sync/engine.ts';
import { Scheduler } from './sync/scheduler.ts';

export interface WireOptions {
  db: Db;
  secrets: SecretBox;
  databaseFile: string | null;
  log: Logger;
  version: string;
  trustedCidrs: readonly string[];
  ownerEmail: string;
  staticDir?: string;
  fakeProviders: boolean;
  manualStaleDays: number;
  connectors?: readonly Connector[];
  enableBanking?: { client: EnableBankingClient; maxConsentDays: number };
  clock?: () => Date;
  remoteAddress?: (c: Context) => string | undefined;
}

export function wire(o: WireOptions) {
  const clock = o.clock ?? (() => new Date());
  const accounts = new AccountsStore(o.db);
  const connections = new ConnectionsStore(o.db, o.secrets);
  const transactions = new TransactionsStore(o.db);
  const netWorthDays = new NetWorthDaysStore(o.db);
  const settings = new SettingsStore(o.db);
  const fake = o.fakeProviders ? new FakeConnector() : undefined;
  const eb = o.enableBanking
    ? {
        flow: new EnableBankingFlow({
          client: o.enableBanking.client,
          connections,
          settings,
          log: o.log,
          maxConsentDays: o.enableBanking.maxConsentDays,
        }),
        connector: new EnableBankingConnector(o.enableBanking.client, o.log),
      }
    : undefined;
  const all: Connector[] = [
    ...(o.connectors ?? []),
    ...(eb ? [eb.connector] : []),
    ...(fake ? [fake] : []),
  ];
  const engine = new SyncEngine({
    db: o.db,
    accounts,
    connections,
    transactions,
    connectors: new Map(all.map((c) => [c.kind, c])),
    log: o.log,
    clock,
  });
  const snapshotNetWorth = (now: Date) => {
    const nw = computeNetWorth(accounts.list());
    netWorthDays.record(now.toISOString().slice(0, 10), nw.household, nw.yours, now.toISOString());
  };
  const scheduler = new Scheduler({
    connections,
    engine,
    log: o.log,
    clock,
    onTick: snapshotNetWorth,
  });
  const app = createApp({
    log: o.log,
    version: o.version,
    trustedCidrs: o.trustedCidrs,
    ownerEmail: o.ownerEmail,
    clock,
    schedulerLastTickAt: () => scheduler.lastTickAt(),
    accounts,
    connections,
    transactions,
    netWorthDays,
    engine,
    health: { databaseFile: o.databaseFile, manualStaleDays: o.manualStaleDays },
    ...(fake ? { fake } : {}),
    ...(eb ? { enableBanking: eb.flow } : {}),
    ...(o.staticDir ? { staticDir: o.staticDir } : {}),
    ...(o.remoteAddress ? { remoteAddress: o.remoteAddress } : {}),
  });
  return { app, scheduler, engine, accounts, connections, transactions, netWorthDays, fake };
}
