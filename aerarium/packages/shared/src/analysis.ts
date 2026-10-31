import { z } from 'zod';

export const Category = z.enum([
  'income',
  'interest',
  'transfer',
  'housing',
  'energy',
  'water',
  'telecoms',
  'council-tax',
  'insurance',
  'childcare',
  'groceries',
  'eating-out',
  'transport',
  'shopping',
  'subscriptions',
  'cash',
  'fees',
  'other',
]);
export type Category = z.infer<typeof Category>;

export interface TxIn {
  id: string;
  accountId: string;
  bookedOn: string;
  minor: number;
  currency: string;
  description: string;
  counterparty: string | null;
}

const RULES: readonly [Category, RegExp][] = [
  ['interest', /\b(interest|int paid|gross int)\b/i],
  ['income', /\b(salary|payroll|wages|pay ?slip|hmrc|child benefit|refund)\b/i],
  ['housing', /\b(mortgage|rent|letting|ground rent|service charge)\b/i],
  ['council-tax', /\bcouncil( tax)?\b|\bctax\b/i],
  ['energy', /\b(energy|electric(ity)?|gas|power|octopus)\b/i],
  ['water', /\bwater\b/i],
  ['telecoms', /\b(broadband|fibre|mobile|telecom|sim only|wifi)\b/i],
  ['insurance', /\b(insurance|insure|assurance|policy)\b/i],
  ['childcare', /\b(nursery|childcare|childminder|tax-free childcare|school)\b/i],
  [
    'groceries',
    /\b(supermarket|grocer(y|ies)?|aldi|lidl|tesco|sainsbury|asda|morrisons|waitrose|co-?op)\b/i,
  ],
  [
    'eating-out',
    /\b(restaurant|cafe|coffee|pizza|takeaway|deliveroo|just eat|uber eats|pub|bar)\b/i,
  ],
  ['transport', /\b(train|rail|bus|taxi|uber|parking|fuel|petrol|tfl|dvla)\b/i],
  [
    'subscriptions',
    /\b(subscription|membership|netflix|spotify|prime|disney|youtube|icloud|patreon)\b/i,
  ],
  ['cash', /\b(atm|cash withdrawal|cashpoint)\b/i],
  ['fees', /\b(fee|charge|overdraft)\b/i],
  ['shopping', /\b(amazon|ebay|argos|ikea|shop|store)\b/i],
];

export function categorise(t: Pick<TxIn, 'description' | 'counterparty' | 'minor'>): Category {
  const text = `${t.counterparty ?? ''} ${t.description}`;
  for (const [cat, re] of RULES) {
    if (re.test(text)) {
      if (cat === 'income' && t.minor < 0) continue;
      return cat;
    }
  }
  return t.minor > 0 ? 'income' : 'other';
}

const DAY = 86_400_000;
const days = (a: string, b: string) => Math.abs(Date.parse(a) - Date.parse(b)) / DAY;

export function findTransfers(txs: readonly TxIn[], windowDays = 3): Set<string> {
  const paired = new Set<string>();
  const credits = txs.filter((t) => t.minor > 0);
  const byAmount = new Map<string, TxIn[]>();
  for (const c of credits) {
    const key = `${c.currency}:${String(c.minor)}`;
    const list = byAmount.get(key) ?? [];
    list.push(c);
    byAmount.set(key, list);
  }
  const debits = txs
    .filter((t) => t.minor < 0)
    .sort((a, b) => a.bookedOn.localeCompare(b.bookedOn));
  for (const d of debits) {
    const candidates = byAmount.get(`${d.currency}:${String(-d.minor)}`) ?? [];
    const match = candidates
      .filter(
        (c) =>
          !paired.has(c.id) &&
          c.accountId !== d.accountId &&
          days(c.bookedOn, d.bookedOn) <= windowDays,
      )
      .sort((a, b) => days(a.bookedOn, d.bookedOn) - days(b.bookedOn, d.bookedOn))[0];
    if (match) {
      paired.add(match.id);
      paired.add(d.id);
    }
  }
  return paired;
}

export function payeeKey(t: Pick<TxIn, 'description' | 'counterparty'>): string {
  const raw = (t.counterparty ?? t.description).toLowerCase();
  return raw
    .replace(
      /\b(dd|so|fp|bp|ddr|card payment|payment to|direct debit|standing order|ref\S*)\b/g,
      ' ',
    )
    .replace(/[^a-z ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export const Cadence = z.enum(['weekly', 'monthly', 'quarterly', 'yearly']);
export type Cadence = z.infer<typeof Cadence>;

const CADENCES: readonly {
  cadence: Cadence;
  days: number;
  tolerance: number;
  minCount: number;
  perMonth: number;
}[] = [
  { cadence: 'weekly', days: 7, tolerance: 1.5, minCount: 4, perMonth: 52 / 12 },
  { cadence: 'monthly', days: 30.4, tolerance: 4, minCount: 3, perMonth: 1 },
  { cadence: 'quarterly', days: 91, tolerance: 10, minCount: 3, perMonth: 1 / 3 },
  { cadence: 'yearly', days: 365, tolerance: 20, minCount: 2, perMonth: 1 / 12 },
];

export const Recurring = z.object({
  key: z.string(),
  payee: z.string(),
  accountId: z.string(),
  category: Category,
  cadence: Cadence,
  typicalMinor: z.number().int(),
  lastMinor: z.number().int(),
  lastOn: z.string(),
  nextDue: z.string(),
  monthlyMinor: z.number().int(),
  count: z.number().int(),
  priceChange: z
    .object({ fromMinor: z.number().int(), toMinor: z.number().int(), percent: z.number() })
    .nullable(),
  lapsed: z.boolean(),
});
export type Recurring = z.infer<typeof Recurring>;

const median = (xs: readonly number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? (s[m] ?? 0) : Math.round(((s[m - 1] ?? 0) + (s[m] ?? 0)) / 2);
};

export function findRecurring(
  txs: readonly TxIn[],
  transfers: ReadonlySet<string>,
  today: string,
): Recurring[] {
  const groups = new Map<string, TxIn[]>();
  for (const t of txs) {
    if (t.minor >= 0 || transfers.has(t.id)) continue;
    const key = `${t.accountId}|${payeeKey(t)}`;
    if (key.endsWith('|')) continue;
    const list = groups.get(key) ?? [];
    list.push(t);
    groups.set(key, list);
  }
  const out: Recurring[] = [];
  for (const [key, list] of groups) {
    const sorted = [...list].sort((a, b) => a.bookedOn.localeCompare(b.bookedOn));
    const gaps = sorted.slice(1).map((t, i) => days(t.bookedOn, sorted[i]?.bookedOn ?? t.bookedOn));
    if (gaps.length === 0) continue;
    const gap = median(gaps);
    const fit = CADENCES.find(
      (c) =>
        sorted.length >= c.minCount &&
        Math.abs(gap - c.days) <= c.tolerance &&
        gaps.filter((g) => Math.abs(g - c.days) <= c.tolerance * 2).length >=
          Math.ceil(gaps.length * 0.7),
    );
    if (!fit) continue;
    const amounts = sorted.map((t) => -t.minor);
    const typical = median(amounts.slice(0, -1).length ? amounts.slice(0, -1) : amounts);
    const spread = Math.max(...amounts) / Math.max(1, Math.min(...amounts));
    if (spread > 3) continue;
    const last = sorted[sorted.length - 1];
    if (!last) continue;
    const lastAmount = -last.minor;
    const change = typical > 0 ? (lastAmount - typical) / typical : 0;
    const next = new Date(Date.parse(last.bookedOn) + fit.days * DAY).toISOString().slice(0, 10);
    out.push({
      key,
      payee: last.counterparty ?? last.description,
      accountId: last.accountId,
      category: categorise(last),
      cadence: fit.cadence,
      typicalMinor: typical,
      lastMinor: lastAmount,
      lastOn: last.bookedOn,
      nextDue: next,
      monthlyMinor: Math.round(lastAmount * fit.perMonth),
      count: sorted.length,
      priceChange:
        Math.abs(change) >= 0.05
          ? { fromMinor: typical, toMinor: lastAmount, percent: Math.round(change * 1000) / 10 }
          : null,
      lapsed: days(today, last.bookedOn) > fit.days + fit.tolerance * 3 && today > next,
    });
  }
  return out.sort((a, b) => b.monthlyMinor - a.monthlyMinor);
}

export interface Outgoings {
  recurring: Recurring[];
  monthlyMinor: number;
  dueSoon: Recurring[];
}

export function outgoings(txs: readonly TxIn[], today: string, soonDays = 14): Outgoings {
  const transfers = findTransfers(txs);
  const recurring = findRecurring(txs, transfers, today);
  const live = recurring.filter((r) => !r.lapsed);
  const horizon = new Date(Date.parse(today) + soonDays * DAY).toISOString().slice(0, 10);
  return {
    recurring,
    monthlyMinor: live.reduce((sum, r) => sum + r.monthlyMinor, 0),
    dueSoon: live
      .filter((r) => r.nextDue >= today && r.nextDue <= horizon)
      .sort((a, b) => a.nextDue.localeCompare(b.nextDue)),
  };
}
