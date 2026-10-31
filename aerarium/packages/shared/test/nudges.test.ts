import { describe, expect, it } from 'vitest';
import { cashIsaCapFor, isaAllowance, reminders, type TxIn } from '../src/index.ts';

let n = 0;
const tx = (accountId: string, bookedOn: string, minor: number, description: string): TxIn => ({
  id: `n${String(n++)}`,
  accountId,
  bookedOn,
  minor,
  currency: 'GBP',
  description,
  counterparty: null,
});

describe('isaAllowance', () => {
  const accounts = [
    { id: 'bank', kind: 'current' as const },
    { id: 'isaA', kind: 'isa-stocks' as const },
    { id: 'isaB', kind: 'isa-cash' as const },
    { id: 'isaC', kind: 'isa-cash' as const },
  ];
  const transactions = [
    tx('bank', '2026-05-01', -500_000, 'TO ISA'),
    tx('isaA', '2026-05-01', 500_000, 'DEPOSIT'),
    tx('isaA', '2026-07-01', 100_000, 'DEPOSIT FROM ELSEWHERE'),
    tx('isaB', '2026-08-01', 2_500, 'GROSS INTEREST'),
    tx('isaB', '2026-09-01', -300_000, 'ISA TRANSFER OUT'),
    tx('isaC', '2026-09-02', 300_000, 'ISA TRANSFER IN'),
    tx('isaA', '2026-03-01', 900_000, 'LAST TAX YEAR'),
  ];

  it('counts money going into ISAs this tax year, not interest, not ISA-to-ISA transfers', () => {
    const a = isaAllowance({
      today: '2026-10-31',
      accounts,
      transactions,
      manual: [
        { id: 'm1', date: '2026-06-15', amountMinor: 250_000, note: 'typed-in cash ISA' },
        { id: 'm0', date: '2026-01-15', amountMinor: 999_999, note: 'last year' },
      ],
    });
    expect(a).toMatchObject({
      taxYearStart: '2026-04-06',
      taxYearEnd: '2027-04-05',
      usedMinor: 850_000,
      leftMinor: 1_150_000,
      daysLeft: 156,
      cashCapMinor: null,
    });
    expect(a.detected.map((d) => d.minor)).toEqual([500_000, 100_000]);
    expect(a.manual.map((m) => m.id)).toEqual(['m1']);
  });

  it('brings in the £12,000 cash ISA cap from April 2027', () => {
    expect(cashIsaCapFor('2026-04-06')).toBeNull();
    expect(cashIsaCapFor('2027-04-06')).toBe(1_200_000);
  });
});

describe('reminders', () => {
  const base = {
    today: '2026-11-01',
    keyDates: [],
    connections: [],
    currentAccounts: [],
    idleThresholdMinor: 300_000,
    idleDays: 30,
  };

  it('lists key dates inside their window, soonest first, and skips past or far ones', () => {
    const r = reminders({
      ...base,
      keyDates: [
        {
          id: 'a',
          title: 'Home insurance renews',
          kind: 'renewal',
          date: '2026-11-20',
          remindDays: 28,
        },
        { id: 'b', title: 'MOT', kind: 'mot', date: '2026-11-05', remindDays: 28 },
        { id: 'c', title: 'Far away', kind: 'other', date: '2027-06-01', remindDays: 28 },
        { id: 'd', title: 'Gone', kind: 'other', date: '2026-10-01', remindDays: 28 },
      ],
    });
    expect(r.map((x) => (x.kind === 'key-date' ? [x.title, x.daysLeft] : null))).toEqual([
      ['MOT', 4],
      ['Home insurance renews', 19],
    ]);
  });

  it('warns about a bank consent in its last fortnight', () => {
    const r = reminders({
      ...base,
      connections: [
        { id: 'c1', label: 'Example Bank', consentExpiresAt: '2026-11-10T09:00:00Z' },
        { id: 'c2', label: 'Other Bank', consentExpiresAt: '2027-01-10T09:00:00Z' },
      ],
    });
    expect(r).toEqual([
      {
        kind: 'consent',
        connectionId: 'c1',
        label: 'Example Bank',
        date: '2026-11-10',
        daysLeft: 9,
      },
    ]);
  });

  it('nudges about cash that sat above the threshold for the whole window, and only then', () => {
    const r = reminders({
      ...base,
      currentAccounts: [
        {
          id: 'idle',
          name: 'Example current',
          history: [
            { asOf: '2026-09-20', minor: 800_000 },
            { asOf: '2026-10-15', minor: 650_000 },
          ],
        },
        {
          id: 'dipped',
          name: 'Dipped',
          history: [
            { asOf: '2026-09-20', minor: 800_000 },
            { asOf: '2026-10-20', minor: 100_000 },
          ],
        },
        { id: 'new', name: 'Too new', history: [{ asOf: '2026-10-25', minor: 900_000 }] },
      ],
    });
    expect(r).toEqual([
      {
        kind: 'idle-cash',
        accountId: 'idle',
        name: 'Example current',
        minMinor: 650_000,
        days: 30,
      },
    ]);
  });
});
