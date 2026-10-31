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
import { MonzoApi, MonzoClientCredentials, MonzoConnector } from './connectors/monzo.ts';
import type { FetchLike } from './http/read-only.ts';
import { TransactionsStore } from './store/transactions.ts';
import { SyncEngine } from './sync/engine.ts';
import { Scheduler } from './sync/scheduler.ts';
import { Snapshots } from './store/snapshot.ts';
import { ReportsStore } from './store/reports.ts';
import { maybeWeekly, type WeeklyDeps } from './notify/weekly.ts';
import { Nudges } from './notify/nudges.ts';
import { buildHealthReport } from './health.ts';
import { LogOnlyNotifier, type Notifier } from './notify/notifier.ts';
import { describeLarge, HealthWatcher } from './notify/watchers.ts';
import { findTransfers } from '@aerarium/shared';

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
  publicUrl?: string;
  monzoFetch?: FetchLike;
  notifier?: Notifier;
  snapshotsDir?: string;
  personalSavingsAllowance?: number;
  idleThresholdMinor?: number;
  largeTransactionMinor?: number;
  clock?: () => Date;
  remoteAddress?: (c: Context) => string | undefined;
}

export function wire(o: WireOptions) {
  const clock = o.clock ?? (() => new Date());
  const accounts = new AccountsStore(o.db);
  const connections = new ConnectionsStore(o.db, o.secrets);
  const transactions = new TransactionsStore(o.db);
  const netWorthDays = new NetWorthDaysStore(o.db);
  const settings = new SettingsStore(o.db, o.secrets);
  const monzoRedirect = o.publicUrl ? `${o.publicUrl.replace(/\/$/, '')}/api/monzo/callback` : null;
  const monzo = monzoRedirect
    ? new MonzoApi(
        () => {
          const stored = settings.getSecret('monzo-client');
          const parsed = MonzoClientCredentials.safeParse(
            typeof stored === 'object' && stored !== null
              ? { ...stored, redirectUrl: monzoRedirect }
              : null,
          );
          return parsed.success ? parsed.data : null;
        },
        o.log,
        o.monzoFetch,
      )
    : undefined;
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
    ...(monzo ? [new MonzoConnector(monzo, o.log)] : []),
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
  const snapshots = o.snapshotsDir
    ? new Snapshots({ db: o.db, dir: o.snapshotsDir, log: o.log })
    : undefined;
  const snapshotNetWorth = (now: Date) => {
    const nw = computeNetWorth(accounts.list());
    netWorthDays.record(now.toISOString().slice(0, 10), nw.household, nw.yours, now.toISOString());
  };
  const notifier = o.notifier ?? new LogOnlyNotifier(o.log);
  const publicUrl = (o.publicUrl ?? '').replace(/\/$/, '');
  const healthWatcher = new HealthWatcher({ settings, notifier, log: o.log, publicUrl });
  const nudges = new Nudges({
    accounts,
    connections,
    transactions,
    settings,
    notifier,
    log: o.log,
    publicUrl,
    idleThresholdMinor: o.idleThresholdMinor ?? 300_000,
  });
  const weekly: WeeklyDeps = {
    accounts,
    transactions,
    netWorthDays,
    reports: new ReportsStore(o.db),
    notifier,
    log: o.log,
    publicUrl,
    personalSavingsAllowance: o.personalSavingsAllowance ?? 100_000,
  };
  let schedulerRef: Scheduler | null = null;
  const healthReport = () =>
    buildHealthReport(
      {
        accounts,
        connections,
        engine,
        version: o.version,
        schedulerLastTickAt: () => schedulerRef?.lastTickAt() ?? null,
        databaseFile: o.databaseFile,
        manualStaleDays: o.manualStaleDays,
        ...(snapshots ? { lastSnapshotDay: () => snapshots.latestDay() } : {}),
      },
      clock(),
    );
  const largeMinor = o.largeTransactionMinor ?? 50_000;
  const alertLarge = async (connectionId: string, runStartedAt: string) => {
    const ids = accounts
      .list()
      .filter((a) => a.connectionId === connectionId)
      .map((a) => a.id);
    const weekAgo = new Date(clock().getTime() - 7 * 86_400_000).toISOString().slice(0, 10);
    const fresh = transactions.addedSince(ids, runStartedAt, weekAgo);
    const transfers = findTransfers(transactions.list({ since: weekAgo, limit: 5000 }));
    const names = new Map(accounts.list().map((a) => [a.id, a.name]));
    const big = fresh.filter((t) => Math.abs(t.minor) >= largeMinor && !transfers.has(t.id));
    o.log.info(
      { connectionId, fresh: fresh.length, large: big.length, thresholdMinor: largeMinor },
      'dewidebug large-transaction check',
    );
    for (const t of big)
      await notifier.send(describeLarge({ ...t, accountName: names.get(t.accountId) ?? '—' }));
  };
  const scheduler = new Scheduler({
    connections,
    engine,
    log: o.log,
    clock,
    onTick: (now) => {
      snapshotNetWorth(now);
      snapshots?.maybeTake(now);
    },
    afterTick: async () => {
      await healthWatcher.check(healthReport());
      await maybeWeekly(weekly, clock());
      await nudges.notifyNew(clock());
    },
    afterRun: alertLarge,
  });
  schedulerRef = scheduler;
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
    healthReport,
    weekly,
    nudges,
    ...(fake ? { fake } : {}),
    ...(eb ? { enableBanking: eb.flow } : {}),
    settings,
    ...(monzo && monzoRedirect ? { monzo: { api: monzo, redirectUrl: monzoRedirect } } : {}),
    ...(o.staticDir ? { staticDir: o.staticDir } : {}),
    ...(o.remoteAddress ? { remoteAddress: o.remoteAddress } : {}),
  });
  return {
    app,
    scheduler,
    engine,
    accounts,
    connections,
    transactions,
    netWorthDays,
    fake,
    notifier,
    healthWatcher,
    healthReport,
  };
}
