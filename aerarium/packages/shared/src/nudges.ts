import { z } from 'zod';
import { categorise, pairTransfers, type TxIn } from './analysis.ts';
import { IsoDate, type AccountKind } from './domain.ts';
import { taxYearStart } from './report.ts';

const DAY = 86_400_000;
const ISA_KINDS: ReadonlySet<AccountKind> = new Set(['isa-cash', 'isa-stocks']);

export const KeyDateKind = z.enum(['renewal', 'maturity', 'fixed-rate-end', 'mot', 'other']);
export type KeyDateKind = z.infer<typeof KeyDateKind>;

export const NewKeyDate = z.object({
  title: z.string().trim().min(1).max(100),
  kind: KeyDateKind,
  date: IsoDate,
  remindDays: z.number().int().min(1).max(365).default(28),
});
export type NewKeyDate = z.infer<typeof NewKeyDate>;
export const KeyDate = NewKeyDate.extend({ id: z.string() });
export type KeyDate = z.infer<typeof KeyDate>;

export const NewIsaSubscription = z.object({
  date: IsoDate,
  amountMinor: z.number().int().positive(),
  note: z.string().trim().max(100).default(''),
});
export type NewIsaSubscription = z.infer<typeof NewIsaSubscription>;
export const IsaSubscription = NewIsaSubscription.extend({ id: z.string() });
export type IsaSubscription = z.infer<typeof IsaSubscription>;

export const Reminder = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('key-date'),
    id: z.string(),
    title: z.string(),
    dateKind: KeyDateKind,
    date: IsoDate,
    daysLeft: z.number().int(),
  }),
  z.object({
    kind: z.literal('consent'),
    connectionId: z.string(),
    label: z.string(),
    date: IsoDate,
    daysLeft: z.number().int(),
  }),
  z.object({
    kind: z.literal('idle-cash'),
    accountId: z.string(),
    name: z.string(),
    minMinor: z.number().int(),
    days: z.number().int(),
  }),
]);
export type Reminder = z.infer<typeof Reminder>;

export const IsaAllowance = z.object({
  taxYearStart: IsoDate,
  taxYearEnd: IsoDate,
  allowanceMinor: z.number().int(),
  cashCapMinor: z.number().int().nullable(),
  usedMinor: z.number().int(),
  cashUsedMinor: z.number().int(),
  leftMinor: z.number().int(),
  daysLeft: z.number().int(),
  detected: z.array(
    z.object({ accountId: z.string(), bookedOn: IsoDate, minor: z.number().int() }),
  ),
  manual: z.array(IsaSubscription),
});
export type IsaAllowance = z.infer<typeof IsaAllowance>;

const daysBetween = (from: string, to: string) =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY);

export function cashIsaCapFor(taxYearStartDay: string): number | null {
  return taxYearStartDay >= '2027-04-06' ? 1_200_000 : null;
}

export function isaAllowance(input: {
  today: string;
  accounts: readonly { id: string; kind: AccountKind }[];
  transactions: readonly TxIn[];
  manual: readonly IsaSubscription[];
}): IsaAllowance {
  const start = taxYearStart(input.today);
  const end = `${String(Number(start.slice(0, 4)) + 1)}-04-05`;
  const kind = new Map(input.accounts.map((a) => [a.id, a.kind]));
  const partner = pairTransfers(input.transactions);
  const detected = input.transactions.filter((t) => {
    const k = kind.get(t.accountId);
    if (!k || !ISA_KINDS.has(k) || t.minor <= 0) return false;
    if (t.bookedOn < start || t.bookedOn > end) return false;
    if (categorise(t) === 'interest') return false;
    const from = partner.get(t.id);
    const fromKind = from ? kind.get(from.accountId) : undefined;
    return !(fromKind && ISA_KINDS.has(fromKind));
  });
  const manual = input.manual.filter((m) => m.date >= start && m.date <= end);
  const used =
    detected.reduce((s, t) => s + t.minor, 0) + manual.reduce((s, m) => s + m.amountMinor, 0);
  const cashUsed = detected
    .filter((t) => kind.get(t.accountId) === 'isa-cash')
    .reduce((s, t) => s + t.minor, 0);
  const allowance = 2_000_000;
  return {
    taxYearStart: start,
    taxYearEnd: end,
    allowanceMinor: allowance,
    cashCapMinor: cashIsaCapFor(start),
    usedMinor: used,
    cashUsedMinor: cashUsed,
    leftMinor: Math.max(0, allowance - used),
    daysLeft: Math.max(0, daysBetween(input.today, end)),
    detected: detected.map((t) => ({
      accountId: t.accountId,
      bookedOn: t.bookedOn,
      minor: t.minor,
    })),
    manual,
  };
}

export function reminders(input: {
  today: string;
  keyDates: readonly KeyDate[];
  connections: readonly { id: string; label: string; consentExpiresAt: string | null }[];
  currentAccounts: readonly {
    id: string;
    name: string;
    history: readonly { asOf: string; minor: number }[];
  }[];
  idleThresholdMinor: number;
  idleDays: number;
}): Reminder[] {
  const out: Reminder[] = [];
  for (const k of input.keyDates) {
    const left = daysBetween(input.today, k.date);
    if (left >= 0 && left <= k.remindDays) {
      out.push({
        kind: 'key-date',
        id: k.id,
        title: k.title,
        dateKind: k.kind,
        date: k.date,
        daysLeft: left,
      });
    }
  }
  for (const c of input.connections) {
    if (!c.consentExpiresAt) continue;
    const day = c.consentExpiresAt.slice(0, 10);
    const left = daysBetween(input.today, day);
    if (left <= 14)
      out.push({ kind: 'consent', connectionId: c.id, label: c.label, date: day, daysLeft: left });
  }
  const from = new Date(Date.parse(`${input.today}T00:00:00Z`) - input.idleDays * DAY)
    .toISOString()
    .slice(0, 10);
  for (const a of input.currentAccounts) {
    const atStart = [...a.history].reverse().find((h) => h.asOf <= from);
    if (!atStart) continue;
    const lowest = Math.min(
      atStart.minor,
      ...a.history.filter((h) => h.asOf > from).map((h) => h.minor),
    );
    if (lowest >= input.idleThresholdMinor) {
      out.push({
        kind: 'idle-cash',
        accountId: a.id,
        name: a.name,
        minMinor: lowest,
        days: input.idleDays,
      });
    }
  }
  const rank = (r: Reminder) => (r.kind === 'idle-cash' ? 1000 : r.daysLeft);
  return out.sort((a, b) => rank(a) - rank(b));
}
