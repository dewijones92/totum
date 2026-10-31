import type { Connector, FetchedTransaction, SyncResult } from './types.ts';

const iso = (d: Date) => d.toISOString().slice(0, 10);

function history(now: Date): FetchedTransaction[] {
  const out: FetchedTransaction[] = [];
  const bills = Array.from(
    { length: 12 },
    (_, m) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 11 + m, 3)),
  ).filter((d) => d <= now);
  for (const [i, bill] of bills.entries()) {
    out.push({
      accountExternalId: 'fake-current',
      externalId: `fake-energy-${iso(bill)}`,
      bookedOn: iso(bill),
      minor: i === bills.length - 1 ? -16_500 : -15_000,
      currency: 'GBP',
      description: 'EXAMPLE ENERGY DD',
      counterparty: 'Example Energy',
    });
    const pay = new Date(Date.UTC(bill.getUTCFullYear(), bill.getUTCMonth(), 1));
    out.push({
      accountExternalId: 'fake-current',
      externalId: `fake-salary-${iso(pay)}`,
      bookedOn: iso(pay),
      minor: 300_000,
      currency: 'GBP',
      description: 'EXAMPLE EMPLOYER SALARY',
      counterparty: null,
    });
  }
  const repay = new Date(now.getTime() - 5 * 86_400_000);
  out.push(
    {
      accountExternalId: 'fake-current',
      externalId: `fake-repay-${iso(repay)}`,
      bookedOn: iso(repay),
      minor: -12_345,
      currency: 'GBP',
      description: 'CARD REPAYMENT',
      counterparty: null,
    },
    {
      accountExternalId: 'fake-card',
      externalId: `fake-repaid-${iso(repay)}`,
      bookedOn: iso(repay),
      minor: 12_345,
      currency: 'GBP',
      description: 'PAYMENT RECEIVED',
      counterparty: null,
    },
  );
  return out;
}

export class FakeConnector implements Connector {
  readonly kind = 'fake' as const;
  readonly everyHours = 6;
  readonly retryAfterHours = 1;
  failWith: string | null = null;

  sync(_connection: unknown, now: Date): Promise<SyncResult> {
    if (this.failWith) return Promise.reject(new Error(this.failWith));
    const today = iso(now);
    return Promise.resolve({
      accounts: [
        {
          externalId: 'fake-current',
          name: 'Example Bank current account',
          kind: 'current',
          currency: 'GBP',
          balance: { minor: 254_321, asOf: today },
        },
        {
          externalId: 'fake-card',
          name: 'Example Card',
          kind: 'credit-card',
          currency: 'GBP',
          balance: { minor: -12_345, asOf: today },
        },
      ],
      transactions: history(now),
    });
  }
}
