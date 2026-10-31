import {
  formatMoney,
  money,
  previousWeek,
  weeklyReport,
  type WeeklyReport,
} from '@aerarium/shared';
import type { Logger } from '../log.ts';
import type { AccountsStore } from '../store/accounts.ts';
import type { NetWorthDaysStore } from '../store/net-worth-days.ts';
import type { ReportsStore } from '../store/reports.ts';
import type { TransactionsStore } from '../store/transactions.ts';
import type { Notifier } from './notifier.ts';

const gbp = (minor: number) => formatMoney(money(minor));
const signed = (minor: number) => `${minor >= 0 ? '+' : '−'}${gbp(Math.abs(minor))}`;

export function headline(r: WeeklyReport, publicUrl: string): string {
  const change =
    r.netWorthChange === null
      ? 'net worth change not known yet'
      : `net worth ${signed(r.netWorthChange)}`;
  const usual = r.usualWeeklySpending === null ? '' : ` (usual ${gbp(r.usualWeeklySpending)})`;
  return `📊 Aerarium week to ${r.weekEnd}: ${change}. Saved ${signed(r.saved)}, markets ${signed(r.markets)}, interest ${gbp(r.interest)}. Spent ${gbp(r.spending)}${usual}. ${publicUrl}/reports`;
}

export interface WeeklyDeps {
  accounts: AccountsStore;
  transactions: TransactionsStore;
  netWorthDays: NetWorthDaysStore;
  reports: ReportsStore;
  notifier: Notifier;
  log: Logger;
  publicUrl: string;
  personalSavingsAllowance: number;
}

export function buildWeekly(d: WeeklyDeps, weekEnd: string): WeeklyReport {
  const since = new Date(Date.parse(`${weekEnd}T00:00:00Z`) - 400 * 86_400_000)
    .toISOString()
    .slice(0, 10);
  return weeklyReport({
    weekEnd,
    accounts: d.accounts.list().map((a) => ({
      id: a.id,
      kind: a.kind,
      balanceAt: (day) => d.accounts.balanceOn(a.id, day),
    })),
    transactions: d.transactions.list({ since, limit: 50_000 }),
    netWorthOn: (day) => d.netWorthDays.on(day)?.household ?? null,
    personalSavingsAllowance: d.personalSavingsAllowance,
  });
}

export async function maybeWeekly(
  d: WeeklyDeps,
  now: Date,
  force = false,
): Promise<WeeklyReport | null> {
  const today = now.toISOString().slice(0, 10);
  const isMondayMorning = now.getUTCDay() === 1 && now.getUTCHours() >= 7;
  const { end } = previousWeek(today);
  if (!force && (!isMondayMorning || d.reports.has(end))) return null;
  const r = buildWeekly(d, end);
  d.reports.save(r, now.toISOString());
  d.log.info(
    {
      weekEnd: end,
      forced: force,
      change: r.netWorthChange,
      saved: r.saved,
      unexplained: r.unexplained,
    },
    'dewidebug weekly report made',
  );
  if (!force) await d.notifier.send(headline(r, d.publicUrl));
  return r;
}
