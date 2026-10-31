import { describe, expect, it } from 'vitest';
import {
  computeNetWorth,
  formatMoney,
  money,
  parseMajorToMinor,
  type Account,
} from '../src/index.ts';

const acct = (over: Partial<Account>): Account => ({
  id: 'a',
  connectionId: 'manual',
  sourceKind: 'manual',
  name: 'Example',
  kind: 'current',
  owner: 'mine',
  currency: 'GBP',
  includeInNetWorth: true,
  balance: money(10_000),
  balanceAsOf: '2026-10-31',
  ...over,
});

describe('money', () => {
  it('formats pounds and pence the British way', () => {
    expect(formatMoney(money(123_456))).toBe('£1,234.56');
    expect(formatMoney(money(-5))).toBe('-£0.05');
  });
  it('parses typed amounts into pence, and refuses nonsense', () => {
    expect(parseMajorToMinor('£1,234.5')).toBe(123_450);
    expect(parseMajorToMinor('-20')).toBe(-2000);
    expect(parseMajorToMinor('12.345')).toBeNull();
    expect(parseMajorToMinor('abc')).toBeNull();
  });
});

describe('computeNetWorth', () => {
  it('subtracts liabilities whatever sign they were stored with', () => {
    const nw = computeNetWorth([
      acct({ id: 'cur', balance: money(500_000) }),
      acct({ id: 'mort', kind: 'mortgage', balance: money(200_000) }),
      acct({ id: 'card', kind: 'credit-card', balance: money(-30_000) }),
    ]);
    expect(nw.household).toBe(270_000);
    expect(nw.assets).toBe(500_000);
    expect(nw.liabilities).toBe(-230_000);
  });
  it('counts half of a joint account as yours', () => {
    const nw = computeNetWorth([acct({ id: 'j', owner: 'joint', balance: money(100_001) })]);
    expect(nw.household).toBe(100_001);
    expect(nw.yours).toBe(50_001);
  });
  it('says why an account was left out', () => {
    const nw = computeNetWorth([
      acct({ id: 'x', includeInNetWorth: false }),
      acct({ id: 'y', balance: null }),
      acct({ id: 'z', balance: money(1, 'USD') }),
    ]);
    expect(nw.counted).toBe(0);
    expect(nw.leftOut.map((l) => l.reason)).toEqual(['excluded', 'no-balance', 'other-currency']);
  });
});
