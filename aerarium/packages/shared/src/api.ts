import { z } from 'zod';
import { Account, Connection, IsoDate, SyncRun } from './domain.ts';
import { Minor } from './money.ts';

export const NetWorthDto = z.object({
  household: Minor,
  yours: Minor,
  assets: Minor,
  liabilities: Minor,
  counted: z.number().int(),
  leftOut: z.array(
    z.object({
      accountId: z.string(),
      reason: z.enum(['excluded', 'no-balance', 'other-currency']),
    }),
  ),
});
export type NetWorthDto = z.infer<typeof NetWorthDto>;

export const NetWorthPoint = z.object({ day: IsoDate, household: z.number(), yours: z.number() });
export type NetWorthPoint = z.infer<typeof NetWorthPoint>;

export const Dashboard = z.object({
  netWorth: NetWorthDto,
  accounts: z.array(Account),
  history: z.array(NetWorthPoint),
});
export type Dashboard = z.infer<typeof Dashboard>;

export const ConnectionsDto = z.array(Connection);
export const SyncRunsDto = z.array(SyncRun);

export const TransactionDto = z.object({
  id: z.string(),
  accountId: z.string(),
  bookedOn: IsoDate,
  minor: z.number().int(),
  currency: z.string(),
  description: z.string(),
  counterparty: z.string().nullable(),
});
export type TransactionDto = z.infer<typeof TransactionDto>;
export const TransactionsDto = z.array(TransactionDto);

export const SyncStarted = z.object({
  outcome: z.enum(['started', 'busy', 'no-connector']),
});
export type SyncStarted = z.infer<typeof SyncStarted>;

export const NewTrading212 = z.object({
  keyId: z.string().trim().min(1),
  secret: z.string().trim().min(1),
  accountKind: z.enum(['isa-stocks', 'investment', 'pension']).default('isa-stocks'),
  accountName: z.string().trim().min(1).max(80).default('Trading 212'),
});
export type NewTrading212 = z.infer<typeof NewTrading212>;
