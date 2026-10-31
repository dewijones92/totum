import { displayBalance, formatMoney, type Account } from '@aerarium/shared';

export function formatBalance(account: Account): string {
  const m = displayBalance(account);
  return m ? formatMoney(m) : '—';
}
