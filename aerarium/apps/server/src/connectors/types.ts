import type { AccountKind } from '@aerarium/shared';
import type { ConnectionRecord } from '../store/connections.ts';

export interface FetchedAccount {
  externalId: string;
  name: string;
  kind: AccountKind;
  currency: string;
  balance: { minor: number; asOf: string } | null;
}

export interface FetchedTransaction {
  accountExternalId: string;
  externalId: string;
  bookedOn: string;
  minor: number;
  currency: string;
  description: string;
  counterparty: string | null;
}

export interface SyncResult {
  accounts: FetchedAccount[];
  transactions: FetchedTransaction[];
  consentExpiresAt?: string | null;
  secrets?: Record<string, unknown>;
}

export interface Connector {
  readonly kind: ConnectionRecord['kind'];
  readonly everyHours: number;
  readonly retryAfterHours: number;
  sync(connection: ConnectionRecord, now: Date): Promise<SyncResult>;
}
