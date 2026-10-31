import { LIABILITY_KINDS, type Account } from './domain.ts';
import { minor, money, type Minor, type Money } from './money.ts';

export interface NetWorth {
  household: Minor;
  yours: Minor;
  assets: Minor;
  liabilities: Minor;
  counted: number;
  leftOut: { accountId: string; reason: 'excluded' | 'no-balance' | 'other-currency' }[];
}

export function signedBalance(account: Account): number {
  if (!account.balance) return 0;
  const v = account.balance.minor;
  return LIABILITY_KINDS.has(account.kind) ? -Math.abs(v) : v;
}

export function displayBalance(account: Account): Money | null {
  if (!account.balance) return null;
  return money(signedBalance(account), account.balance.currency);
}

export function computeNetWorth(accounts: readonly Account[], currency = 'GBP'): NetWorth {
  let household = 0;
  let yours = 0;
  let assets = 0;
  let liabilities = 0;
  let counted = 0;
  const leftOut: NetWorth['leftOut'] = [];
  for (const a of accounts) {
    if (!a.includeInNetWorth) {
      leftOut.push({ accountId: a.id, reason: 'excluded' });
      continue;
    }
    if (!a.balance) {
      leftOut.push({ accountId: a.id, reason: 'no-balance' });
      continue;
    }
    if (a.balance.currency !== currency) {
      leftOut.push({ accountId: a.id, reason: 'other-currency' });
      continue;
    }
    const v = signedBalance(a);
    counted++;
    household += v;
    yours += a.owner === 'joint' ? Math.round(v / 2) : v;
    if (v >= 0) assets += v;
    else liabilities += v;
  }
  return {
    household: minor(household),
    yours: minor(yours),
    assets: minor(assets),
    liabilities: minor(liabilities),
    counted,
    leftOut,
  };
}
