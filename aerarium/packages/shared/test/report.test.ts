import { describe, expect, it } from 'vitest';
import {
  previousWeek,
  taxYearStart,
  weeklyReport,
  type ReportAccount,
  type TxIn,
} from '../src/index.ts';

let n = 0;
const tx = (accountId: string, bookedOn: string, pounds: number, description: string): TxIn => ({
  id: `r${String(n++)}`,
  accountId,
  bookedOn,
  minor: Math.round(pounds * 100),
  currency: 'GBP',
  description,
  counterparty: null,
});

const balances: Record<string, Record<string, number>> = {
  cur: { '2026-10-25': 100_000, '2026-11-01': 150_000 },
  card: { '2026-10-25': -20_000, '2026-11-01': -10_000 },
  isa: { '2026-10-25': 1_000_000, '2026-11-01': 1_060_000 },
  sav: { '2026-10-25': 500_000, '2026-11-01': 501_000 },
};
const account = (id: string, kind: ReportAccount['kind']): ReportAccount => ({
  id,
  kind,
  balanceAt: (day) => balances[id]?.[day] ?? null,
});

function input() {
  const t: TxIn[] = [
    tx('cur', '2026-10-28', 2000, 'EXAMPLE EMPLOYER SALARY'),
    tx('card', '2026-10-27', -100, 'TESCO STORES'),
    tx('cur', '2026-10-29', -150, 'EXAMPLE ENERGY DD'),
    tx('cur', '2026-10-30', -200, 'CARD REPAYMENT'),
    tx('card', '2026-10-30', 200, 'PAYMENT RECEIVED'),
    tx('cur', '2026-10-27', -500, 'TO ISA'),
    tx('isa', '2026-10-27', 500, 'DEPOSIT'),
    tx('sav', '2026-10-31', 10, 'GROSS INTEREST'),
    tx('sav', '2026-05-01', 20, 'GROSS INTEREST'),
    tx('isa', '2026-06-01', 50, 'INTEREST ON CASH'),
    tx('sav', '2026-03-01', 99, 'GROSS INTEREST'),
  ];
  for (let w = 1; w <= 10; w++) {
    t.push(
      tx(
        'card',
        new Date(Date.parse('2026-10-26') - w * 7 * 86_400_000).toISOString().slice(0, 10),
        -100,
        'TESCO STORES',
      ),
    );
  }
  const nw: Record<string, number> = {
    '2026-01-01': 2_000_000,
    '2026-10-25': 2_500_000,
    '2026-11-01': 2_686_200,
  };
  return {
    weekEnd: '2026-11-01',
    accounts: [
      account('cur', 'current'),
      account('card', 'credit-card'),
      account('isa', 'isa-stocks'),
      account('sav', 'savings'),
    ],
    transactions: t,
    netWorthOn: (d: string) => nw[d] ?? null,
    personalSavingsAllowance: 100_000,
  };
}

describe('weeklyReport', () => {
  const r = weeklyReport(input());

  it('splits the week into saved, interest, markets and debt, and admits what it cannot explain', () => {
    expect(r).toMatchObject({
      weekStart: '2026-10-26',
      weekEnd: '2026-11-01',
      income: 200_000,
      spending: 25_000,
      saved: 175_000,
      interest: 1_000,
      markets: 10_000,
      debtPaidDown: 10_000,
      netWorthChange: 186_200,
      unexplained: 200,
    });
  });

  it('compares spending with a usual week, by category', () => {
    expect(r.usualWeeklySpending).toBe(10_000);
    expect(r.topCategories).toEqual([
      { category: 'energy', minor: 15_000, usualMinor: 0 },
      { category: 'groceries', minor: 10_000, usualMinor: 10_000 },
    ]);
  });

  it('gives the year so far, a projection and the savings rate', () => {
    expect(r.yearToDateChange).toBe(686_200);
    expect(r.savingsRatePercent).toBe(87.5);
    expect(r.projection12Months).toBe(
      Math.round(
        (686_200 / ((Date.parse('2026-11-01') - Date.parse('2026-01-01')) / (7 * 86_400_000))) * 52,
      ),
    );
  });

  it('counts only interest outside ISAs since 6 April toward the allowance', () => {
    expect(r.taxYearStart).toBe('2026-04-06');
    expect(r.interestOutsideIsaThisTaxYear).toBe(3_000);
  });
});

describe('dates', () => {
  it('finds last Monday-to-Sunday from any day of this week', () => {
    expect(previousWeek('2026-11-02')).toEqual({ start: '2026-10-26', end: '2026-11-01' });
    expect(previousWeek('2026-11-08')).toEqual({ start: '2026-10-26', end: '2026-11-01' });
  });
  it('knows the tax year turns on 6 April', () => {
    expect(taxYearStart('2026-04-05')).toBe('2025-04-06');
    expect(taxYearStart('2026-04-06')).toBe('2026-04-06');
  });
});

describe('currency', () => {
  it('leaves non-pound transactions out of the report', () => {
    const base = input();
    const usd: TxIn = { ...tx('cur', '2026-10-29', -999, 'US SHOP'), currency: 'USD' };
    const r = weeklyReport({ ...base, transactions: [...base.transactions, usd] });
    expect(r.spending).toBe(25_000);
  });
});
