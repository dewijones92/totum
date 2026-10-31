import { describe, expect, it } from 'vitest';
import {
  categorise,
  findRecurring,
  findTransfers,
  outgoings,
  payeeKey,
  type TxIn,
} from '../src/index.ts';

let n = 0;
const tx = (
  accountId: string,
  bookedOn: string,
  minor: number,
  description: string,
  counterparty: string | null = null,
): TxIn => ({
  id: `t${String(n++)}`,
  accountId,
  bookedOn,
  minor,
  currency: 'GBP',
  description,
  counterparty,
});

const months = (from: number, to: number, day = 5) =>
  Array.from(
    { length: to - from + 1 },
    (_, i) => `2026-${String(from + i).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
  );

function year(): TxIn[] {
  const t: TxIn[] = [];
  for (const [i, d] of months(1, 10).entries())
    t.push(tx('cur', d, i === 9 ? -16_500 : -15_000, 'EXAMPLE ENERGY DD', 'Example Energy'));
  for (const d of months(1, 10, 25)) t.push(tx('cur', d, 300_000, 'EXAMPLE EMPLOYER SALARY'));
  t.push(
    tx('cur', '2025-03-01', -7_999, 'EXAMPLE STREAMING SUBSCRIPTION'),
    tx('cur', '2026-03-01', -7_999, 'EXAMPLE STREAMING SUBSCRIPTION'),
  );
  for (const d of months(1, 6, 2)) t.push(tx('cur', d, -3_000, 'EXAMPLE GYM MEMBERSHIP'));
  for (const d of ['2026-02-03', '2026-02-09', '2026-04-21', '2026-07-30', '2026-08-02'])
    t.push(tx('card', d, -450, 'EXAMPLE CAFE COFFEE'));
  t.push(
    tx('cur', '2026-05-10', -50_000, 'TO SAVINGS'),
    tx('sav', '2026-05-11', 50_000, 'FROM CURRENT'),
  );
  t.push(
    tx('cur', '2026-06-20', -23_000, 'CARD REPAYMENT'),
    tx('card', '2026-06-21', 23_000, 'PAYMENT RECEIVED'),
  );
  t.push(tx('sav', '2026-06-30', 1_234, 'GROSS INTEREST'));
  return t;
}

describe('categorise', () => {
  it('reads common words, never calls an outflow income', () => {
    expect(categorise({ description: 'COUNCIL TAX DD', counterparty: null, minor: -1 })).toBe(
      'council-tax',
    );
    expect(categorise({ description: 'GROSS INTEREST', counterparty: null, minor: 1 })).toBe(
      'interest',
    );
    expect(categorise({ description: 'HMRC SELF ASSESSMENT', counterparty: null, minor: -1 })).toBe(
      'other',
    );
    expect(categorise({ description: 'MYSTERY', counterparty: null, minor: 100 })).toBe('income');
  });
});

describe('findTransfers', () => {
  it('pairs money moved between your own accounts, including a card repayment', () => {
    const t = year();
    const ids = findTransfers(t);
    const moved = t
      .filter((x) => ids.has(x.id))
      .map((x) => `${x.accountId}:${String(x.minor)}`)
      .sort();
    expect(moved).toEqual(['card:23000', 'cur:-23000', 'cur:-50000', 'sav:50000']);
  });

  it('does not pair two movements inside the same account or too far apart', () => {
    const t = [
      tx('a', '2026-01-01', -100, 'x'),
      tx('a', '2026-01-01', 100, 'y'),
      tx('b', '2026-01-20', 100, 'z'),
    ];
    expect(findTransfers(t).size).toBe(0);
  });
});

describe('findRecurring', () => {
  const t = year();
  const r = findRecurring(t, findTransfers(t), '2026-10-31');
  const by = (re: RegExp) => r.find((x) => re.test(x.payee));

  it('finds the monthly bill, flags its rise, and predicts the next one', () => {
    expect(by(/Energy/)).toMatchObject({
      cadence: 'monthly',
      category: 'energy',
      typicalMinor: 15_000,
      lastMinor: 16_500,
      priceChange: { fromMinor: 15_000, toMinor: 16_500, percent: 10 },
      lapsed: false,
      monthlyMinor: 16_500,
    });
    expect(by(/Energy/)!.nextDue.startsWith('2026-11-0')).toBe(true);
  });

  it('finds a yearly subscription and spreads it per month', () => {
    expect(by(/STREAMING/)).toMatchObject({ cadence: 'yearly', monthlyMinor: 667 });
  });

  it('marks a payment that stopped as lapsed', () => {
    expect(by(/GYM/)).toMatchObject({ cadence: 'monthly', lapsed: true });
  });

  it('ignores irregular spending, transfers and income', () => {
    expect(by(/CAFE/)).toBeUndefined();
    expect(by(/SAVINGS|REPAYMENT|SALARY/)).toBeUndefined();
  });
});

describe('outgoings', () => {
  it('adds up only live recurring payments, and lists what is due soon', () => {
    const o = outgoings(year(), '2026-10-31');
    expect(o.monthlyMinor).toBe(16_500 + 667);
    expect(o.dueSoon.map((x) => x.payee)).toEqual(['Example Energy']);
  });

  it('normalises payee names', () => {
    expect(payeeKey({ description: 'DD EXAMPLE ENERGY REF123456', counterparty: null })).toBe(
      'example energy',
    );
  });
});
