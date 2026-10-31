import { z } from 'zod';
import { Currency, Minor, Money } from './money.ts';

export const SourceKind = z.enum(['manual', 'enable-banking', 'monzo', 'trading212', 'fake']);
export type SourceKind = z.infer<typeof SourceKind>;

export const AccountKind = z.enum([
  'current',
  'savings',
  'isa-cash',
  'isa-stocks',
  'investment',
  'pension',
  'credit-card',
  'mortgage',
  'loan',
  'childcare',
  'other',
]);
export type AccountKind = z.infer<typeof AccountKind>;

export const LIABILITY_KINDS: ReadonlySet<AccountKind> = new Set([
  'credit-card',
  'mortgage',
  'loan',
]);

export const Owner = z.enum(['mine', 'joint']);
export type Owner = z.infer<typeof Owner>;

export const IsoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
export type IsoDate = z.infer<typeof IsoDate>;

export const IsoInstant = z.iso.datetime({ offset: true });
export type IsoInstant = z.infer<typeof IsoInstant>;

export const Account = z.object({
  id: z.string(),
  connectionId: z.string(),
  sourceKind: SourceKind,
  name: z.string(),
  kind: AccountKind,
  owner: Owner,
  currency: Currency,
  includeInNetWorth: z.boolean(),
  ownerConfirmed: z.boolean(),
  balance: Money.nullable(),
  balanceAsOf: IsoDate.nullable(),
});
export type Account = z.infer<typeof Account>;

export const NewManualAccount = z.object({
  name: z.string().trim().min(1).max(80),
  kind: AccountKind,
  owner: Owner,
  currency: Currency.default('GBP'),
  balanceMinor: Minor,
  asOf: IsoDate,
});
export type NewManualAccount = z.infer<typeof NewManualAccount>;

export const ManualBalance = z.object({ balanceMinor: Minor, asOf: IsoDate });
export type ManualBalance = z.infer<typeof ManualBalance>;

export const AccountPatch = z
  .object({
    name: z.string().trim().min(1).max(80),
    kind: AccountKind,
    owner: Owner,
    includeInNetWorth: z.boolean(),
  })
  .partial();
export type AccountPatch = z.infer<typeof AccountPatch>;

export const SyncOutcome = z.enum(['running', 'ok', 'failed']);
export type SyncOutcome = z.infer<typeof SyncOutcome>;

export const SyncRun = z.object({
  id: z.number().int(),
  connectionId: z.string(),
  startedAt: IsoInstant,
  finishedAt: IsoInstant.nullable(),
  outcome: SyncOutcome,
  accountsSeen: z.number().int(),
  transactionsAdded: z.number().int(),
  error: z.string().nullable(),
  warning: z.string().nullable().default(null),
});
export type SyncRun = z.infer<typeof SyncRun>;

export const Connection = z.object({
  id: z.string(),
  kind: SourceKind,
  label: z.string(),
  consentExpiresAt: IsoInstant.nullable(),
  lastRun: SyncRun.nullable(),
  lastOkAt: IsoInstant.nullable(),
});
export type Connection = z.infer<typeof Connection>;
