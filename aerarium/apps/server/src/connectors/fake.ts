import type { Connector, SyncResult } from './types.ts';

export class FakeConnector implements Connector {
  readonly kind = 'fake' as const;
  readonly everyHours = 6;
  readonly retryAfterHours = 1;
  failWith: string | null = null;

  sync(_connection: unknown, now: Date): Promise<SyncResult> {
    if (this.failWith) return Promise.reject(new Error(this.failWith));
    const today = now.toISOString().slice(0, 10);
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
      transactions: [
        {
          accountExternalId: 'fake-current',
          externalId: `fake-tx-${today}`,
          bookedOn: today,
          minor: -4_250,
          currency: 'GBP',
          description: 'EXAMPLE ENERGY DD',
          counterparty: 'Example Energy',
        },
      ],
    });
  }
}
