import { z } from 'zod';
import { categorise, findTransfers, type TxIn } from './analysis.ts';
import { LIABILITY_KINDS, type AccountKind } from './domain.ts';

const DAY = 86_400_000;
const INVESTMENT_KINDS: ReadonlySet<AccountKind> = new Set(['isa-stocks', 'investment', 'pension']);
const ISA_KINDS: ReadonlySet<AccountKind> = new Set(['isa-cash', 'isa-stocks']);

export const WeeklyReport = z.object({
  weekStart: z.string(),
  weekEnd: z.string(),
  netWorthStart: z.number().int().nullable(),
  netWorthEnd: z.number().int().nullable(),
  netWorthChange: z.number().int().nullable(),
  income: z.number().int(),
  spending: z.number().int(),
  saved: z.number().int(),
  interest: z.number().int(),
  markets: z.number().int(),
  debtPaidDown: z.number().int(),
  unexplained: z.number().int().nullable(),
  usualWeeklySpending: z.number().int().nullable(),
  topCategories: z.array(
    z.object({ category: z.string(), minor: z.number().int(), usualMinor: z.number().int() }),
  ),
  yearToDateChange: z.number().int().nullable(),
  projection12Months: z.number().int().nullable(),
  savingsRatePercent: z.number().nullable(),
  taxYearStart: z.string(),
  interestOutsideIsaThisTaxYear: z.number().int(),
  personalSavingsAllowance: z.number().int(),
});
export type WeeklyReport = z.infer<typeof WeeklyReport>;

export interface ReportAccount {
  id: string;
  kind: AccountKind;
  balanceAt: (day: string) => number | null;
}

export interface ReportInput {
  weekEnd: string;
  accounts: readonly ReportAccount[];
  transactions: readonly TxIn[];
  netWorthOn: (day: string) => number | null;
  personalSavingsAllowance: number;
}

const iso = (t: number) => new Date(t).toISOString().slice(0, 10);

export function previousWeek(today: string): { start: string; end: string } {
  const d = new Date(`${today}T00:00:00Z`);
  const sinceMonday = (d.getUTCDay() + 6) % 7;
  const thisMonday = d.getTime() - sinceMonday * DAY;
  return { start: iso(thisMonday - 7 * DAY), end: iso(thisMonday - DAY) };
}

export function taxYearStart(day: string): string {
  const year = Number(day.slice(0, 4));
  const start = `${String(year)}-04-06`;
  return day >= start ? start : `${String(year - 1)}-04-06`;
}

export function weeklyReport(input: ReportInput): WeeklyReport {
  const end = input.weekEnd;
  const start = iso(Date.parse(`${end}T00:00:00Z`) - 6 * DAY);
  const before = iso(Date.parse(`${start}T00:00:00Z`) - DAY);
  const gbp = input.transactions.filter((t) => t.currency === 'GBP');
  const transfers = findTransfers(gbp);
  const kindOf = new Map(input.accounts.map((a) => [a.id, a.kind]));
  const inWeek = (t: TxIn) => t.bookedOn >= start && t.bookedOn <= end;

  let income = 0;
  let spending = 0;
  let interest = 0;
  const weekByCat = new Map<string, number>();
  for (const t of gbp) {
    if (!inWeek(t) || transfers.has(t.id)) continue;
    const kind = kindOf.get(t.accountId);
    if (kind && INVESTMENT_KINDS.has(kind)) continue;
    const cat = categorise(t);
    if (cat === 'interest' && t.minor > 0) interest += t.minor;
    else if (t.minor > 0) income += t.minor;
    else {
      spending += -t.minor;
      weekByCat.set(cat, (weekByCat.get(cat) ?? 0) + -t.minor);
    }
  }

  let markets = 0;
  let debtPaidDown = 0;
  for (const a of input.accounts) {
    const b0 = a.balanceAt(before);
    const b1 = a.balanceAt(end);
    if (b0 === null || b1 === null) continue;
    if (INVESTMENT_KINDS.has(a.kind)) {
      const deposits = gbp
        .filter((t) => t.accountId === a.id && inWeek(t))
        .reduce((s, t) => s + t.minor, 0);
      markets += b1 - b0 - deposits;
    } else if (LIABILITY_KINDS.has(a.kind)) {
      debtPaidDown += Math.abs(b0) - Math.abs(b1);
    }
  }

  const yearAgo = iso(Date.parse(`${end}T00:00:00Z`) - 364 * DAY);
  let pastSpending = 0;
  const pastByCat = new Map<string, number>();
  let earliest: string | null = null;
  for (const t of gbp) {
    if (t.bookedOn < yearAgo || t.bookedOn >= start || transfers.has(t.id) || t.minor >= 0)
      continue;
    const kind = kindOf.get(t.accountId);
    if (kind && INVESTMENT_KINDS.has(kind)) continue;
    pastSpending += -t.minor;
    const cat = categorise(t);
    pastByCat.set(cat, (pastByCat.get(cat) ?? 0) + -t.minor);
    if (earliest === null || t.bookedOn < earliest) earliest = t.bookedOn;
  }
  const pastWeeks = earliest
    ? Math.max(1, Math.round((Date.parse(start) - Date.parse(earliest)) / (7 * DAY)))
    : 0;
  const usual = pastWeeks > 0 ? Math.round(pastSpending / pastWeeks) : null;
  const topCategories = [...weekByCat.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([category, minor]) => ({
      category,
      minor,
      usualMinor: pastWeeks > 0 ? Math.round((pastByCat.get(category) ?? 0) / pastWeeks) : 0,
    }));

  const nw0 = input.netWorthOn(before);
  const nw1 = input.netWorthOn(end);
  const change = nw0 !== null && nw1 !== null ? nw1 - nw0 : null;
  const saved = income - spending;
  const jan1 = `${end.slice(0, 4)}-01-01`;
  const nwJan = input.netWorthOn(jan1);
  const ytd = nwJan !== null && nw1 !== null ? nw1 - nwJan : null;
  const firstKnownDay = [jan1, start].find((d) => input.netWorthOn(d) !== null) ?? null;
  const weeksKnown = firstKnownDay ? (Date.parse(end) - Date.parse(firstKnownDay)) / (7 * DAY) : 0;
  const nwFirst = firstKnownDay ? input.netWorthOn(firstKnownDay) : null;
  const projection =
    nwFirst !== null && nw1 !== null && weeksKnown >= 1
      ? Math.round(((nw1 - nwFirst) / weeksKnown) * 52)
      : null;

  const tys = taxYearStart(end);
  let interestOutsideIsa = 0;
  for (const t of gbp) {
    if (t.bookedOn < tys || t.bookedOn > end || t.minor <= 0 || transfers.has(t.id)) continue;
    const kind = kindOf.get(t.accountId);
    if (kind && ISA_KINDS.has(kind)) continue;
    if (categorise(t) === 'interest') interestOutsideIsa += t.minor;
  }

  return {
    weekStart: start,
    weekEnd: end,
    netWorthStart: nw0,
    netWorthEnd: nw1,
    netWorthChange: change,
    income,
    spending,
    saved,
    interest,
    markets,
    debtPaidDown,
    unexplained: change === null ? null : change - saved - interest - markets,
    usualWeeklySpending: usual,
    topCategories,
    yearToDateChange: ytd,
    projection12Months: projection,
    savingsRatePercent: income > 0 ? Math.round((saved / income) * 1000) / 10 : null,
    taxYearStart: tys,
    interestOutsideIsaThisTaxYear: interestOutsideIsa,
    personalSavingsAllowance: input.personalSavingsAllowance,
  };
}
