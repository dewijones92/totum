import { createHash, createSign } from 'node:crypto';
import { AccountKind } from '@aerarium/shared';
import { z } from 'zod';
import { ReadOnlyHttp, type Allowed, type FetchLike } from '../http/read-only.ts';
import type { Logger } from '../log.ts';
import type { ConnectionRecord } from '../store/connections.ts';
import type { Connector, FetchedAccount, FetchedTransaction, SyncResult } from './types.ts';

const HOST = 'api.enablebanking.com';
const BASE = `https://${HOST}`;
const UUID = '[0-9a-fA-F-]{36}';

export const ENABLE_BANKING_ALLOWED: readonly Allowed[] = [
  { method: 'GET', host: HOST, path: /^\/aspsps$/ },
  { method: 'POST', host: HOST, path: /^\/auth$/ },
  { method: 'POST', host: HOST, path: /^\/sessions$/ },
  { method: 'DELETE', host: HOST, path: new RegExp(`^/sessions/${UUID}$`) },
  { method: 'GET', host: HOST, path: new RegExp(`^/accounts/${UUID}/balances$`) },
  { method: 'GET', host: HOST, path: new RegExp(`^/accounts/${UUID}/transactions$`) },
];

export interface EnableBankingSettings {
  applicationId: string;
  privateKeyPem: string;
  redirectUrl: string;
}

const Amount = z.object({ amount: z.string(), currency: z.string() });

export const Aspsp = z.object({
  name: z.string(),
  country: z.string(),
  maximum_consent_validity: z.number().optional(),
  logo: z.string().optional(),
  psu_types: z.array(z.string()).optional(),
});
export type Aspsp = z.infer<typeof Aspsp>;

const SessionAccount = z.object({
  uid: z.string(),
  identification_hash: z.string().nullable().optional(),
  name: z.string().nullable().optional(),
  details: z.string().nullable().optional(),
  product: z.string().nullable().optional(),
  currency: z.string().nullable().optional(),
  cash_account_type: z.string().nullable().optional(),
});

const SessionCreated = z.object({
  session_id: z.string(),
  accounts: z.array(SessionAccount),
  aspsp: z.object({ name: z.string(), country: z.string() }),
  access: z.object({ valid_until: z.string() }),
});
export type SessionCreated = z.infer<typeof SessionCreated>;

const Balances = z.object({
  balances: z.array(
    z.object({
      balance_amount: Amount,
      balance_type: z.string(),
      reference_date: z.string().nullable().optional(),
    }),
  ),
});

const Party = z.object({ name: z.string().nullable().optional() }).nullable().optional();
const Transactions = z.object({
  transactions: z.array(
    z.object({
      entry_reference: z.string().nullable().optional(),
      transaction_id: z.string().nullable().optional(),
      transaction_amount: Amount,
      credit_debit_indicator: z.enum(['CRDT', 'DBIT']),
      status: z.string(),
      booking_date: z.string().nullable().optional(),
      value_date: z.string().nullable().optional(),
      transaction_date: z.string().nullable().optional(),
      remittance_information: z.array(z.string()).nullable().optional(),
      creditor: Party,
      debtor: Party,
    }),
  ),
  continuation_key: z.string().nullable().optional(),
});

export const EnableBankingConfig = z.object({
  aspspName: z.string(),
  aspspCountry: z.string(),
  accounts: z.array(
    z.object({
      uid: z.string(),
      key: z.string(),
      name: z.string(),
      kind: AccountKind,
      currency: z.string(),
    }),
  ),
  syncedThrough: z.record(z.string(), z.string()).default({}),
});

export const EnableBankingSecrets = z.object({ sessionId: z.string() });

const BALANCE_PREFERENCE = ['CLBD', 'ITBD', 'CLAV', 'ITAV', 'XPCD', 'OPBD', 'PRCD', 'INFO', 'OTHR'];

function rank(type: string): number {
  const i = BALANCE_PREFERENCE.indexOf(type);
  return i === -1 ? BALANCE_PREFERENCE.length : i;
}

export function kindFromCashAccountType(t: string | null | undefined): AccountKind {
  switch (t ?? '') {
    case 'CARD':
      return 'credit-card';
    case 'SVGS':
    case 'MOMA':
      return 'savings';
    case 'LOAN':
      return 'loan';
    case 'MGLD':
      return 'mortgage';
    default:
      return 'current';
  }
}

export function accountName(a: z.infer<typeof SessionAccount>, aspspName: string): string {
  return a.name ?? a.details ?? a.product ?? `${aspspName} account`;
}

const toMinor = (amount: string): number => {
  const n = Number(amount);
  if (!Number.isFinite(n)) throw new Error(`Unreadable amount from the bank: ${amount}`);
  return Math.round(n * 100);
};

function describe(remittance: string[] | null | undefined, counterparty: string | null): string {
  const text = (remittance ?? []).join(' ').trim();
  if (text !== '') return text;
  return counterparty ?? '—';
}

function b64url(input: Buffer | string): string {
  return Buffer.from(input).toString('base64url');
}

export function signJwt(s: EnableBankingSettings, now: Date): string {
  const iat = Math.floor(now.getTime() / 1000);
  const header = b64url(JSON.stringify({ typ: 'JWT', alg: 'RS256', kid: s.applicationId }));
  const payload = b64url(
    JSON.stringify({
      iss: 'enablebanking.com',
      aud: 'api.enablebanking.com',
      iat,
      exp: iat + 3600,
    }),
  );
  const signer = createSign('RSA-SHA256');
  signer.update(`${header}.${payload}`);
  return `${header}.${payload}.${b64url(signer.sign(s.privateKeyPem))}`;
}

export function stableKey(a: {
  uid: string;
  identification_hash?: string | null | undefined;
}): string {
  return a.identification_hash ?? a.uid;
}

function transactionBasis(
  key: string,
  t: z.infer<typeof Transactions>['transactions'][number],
): string {
  return [
    key,
    t.booking_date ?? t.value_date ?? '',
    t.transaction_amount.amount,
    t.credit_debit_indicator,
    (t.remittance_information ?? []).join('|'),
  ].join('#');
}

function hashed(basis: string, occurrence: number): string {
  const digest = createHash('sha256')
    .update(`${basis}#${String(occurrence)}`)
    .digest('hex');
  return `hash:${digest.slice(0, 32)}`;
}

export class EnableBankingClient {
  readonly http: ReadOnlyHttp;

  constructor(
    private readonly settings: EnableBankingSettings,
    log: Logger,
    fetchImpl?: FetchLike,
  ) {
    this.http = new ReadOnlyHttp({
      provider: 'Enable Banking',
      allowed: ENABLE_BANKING_ALLOWED,
      log,
      ...(fetchImpl ? { fetch: fetchImpl } : {}),
    });
  }

  private headers(now: Date, json = false): Record<string, string> {
    return {
      authorization: `Bearer ${signJwt(this.settings, now)}`,
      ...(json ? { 'content-type': 'application/json' } : {}),
    };
  }

  async banks(country: string, now: Date): Promise<Aspsp[]> {
    const body = z
      .object({ aspsps: z.array(Aspsp) })
      .parse(
        await this.http.json(
          'GET',
          `${BASE}/aspsps?country=${encodeURIComponent(country)}&psu_type=personal`,
          { headers: this.headers(now) },
        ),
      );
    return body.aspsps;
  }

  async startAuth(
    aspsp: { name: string; country: string },
    validUntil: Date,
    state: string,
    now: Date,
  ): Promise<{ url: string }> {
    const body = await this.http.json('POST', `${BASE}/auth`, {
      headers: this.headers(now, true),
      body: JSON.stringify({
        access: { valid_until: validUntil.toISOString() },
        aspsp,
        state,
        redirect_url: this.settings.redirectUrl,
        psu_type: 'personal',
      }),
    });
    return z.object({ url: z.string() }).parse(body);
  }

  async createSession(code: string, now: Date): Promise<SessionCreated> {
    return SessionCreated.parse(
      await this.http.json('POST', `${BASE}/sessions`, {
        headers: this.headers(now, true),
        body: JSON.stringify({ code }),
      }),
    );
  }

  async balances(uid: string, now: Date) {
    return Balances.parse(
      await this.http.json('GET', `${BASE}/accounts/${uid}/balances`, {
        headers: this.headers(now),
      }),
    ).balances;
  }

  async transactions(uid: string, dateFrom: string, continuation: string | null, now: Date) {
    const q = new URLSearchParams({ date_from: dateFrom });
    if (continuation) q.set('continuation_key', continuation);
    return Transactions.parse(
      await this.http.json('GET', `${BASE}/accounts/${uid}/transactions?${q.toString()}`, {
        headers: this.headers(now),
      }),
    );
  }
}

export class EnableBankingConnector implements Connector {
  readonly kind = 'enable-banking' as const;
  readonly everyHours = 6;
  readonly retryAfterHours = 6;

  constructor(
    private readonly client: EnableBankingClient,
    private readonly log: Logger,
    private readonly firstSyncDays = 730,
    private readonly maxPages = 50,
  ) {}

  async sync(connection: ConnectionRecord, now: Date): Promise<SyncResult> {
    const config = EnableBankingConfig.parse(connection.config);
    EnableBankingSecrets.parse(connection.secrets);
    const accounts: FetchedAccount[] = [];
    const transactions: FetchedTransaction[] = [];
    const syncedThrough: Record<string, string> = { ...config.syncedThrough };
    const warnings: string[] = [];
    const today = now.toISOString().slice(0, 10);
    for (const a of config.accounts) {
      const balances = await this.client.balances(a.uid, now);
      const pick = [...balances].sort((x, y) => rank(x.balance_type) - rank(y.balance_type))[0];
      this.log.info(
        {
          connection: connection.id,
          account: a.uid,
          types: balances.map((b) => b.balance_type),
          picked: pick?.balance_type ?? null,
        },
        'dewidebug enable banking balance chosen',
      );
      accounts.push({
        externalId: a.key,
        name: a.name,
        kind: a.kind,
        currency: pick?.balance_amount.currency ?? a.currency,
        balance: pick
          ? { minor: toMinor(pick.balance_amount.amount), asOf: pick.reference_date ?? today }
          : null,
      });
      const last = syncedThrough[a.key];
      const from = last
        ? new Date(Date.parse(last) - 10 * 86_400_000).toISOString().slice(0, 10)
        : new Date(now.getTime() - this.firstSyncDays * 86_400_000).toISOString().slice(0, 10);
      let continuation: string | null = null;
      let pages = 0;
      let booked = 0;
      let skipped = 0;
      let newest: string | null = null;
      const seen = new Map<string, number>();
      do {
        const page = await this.client.transactions(a.uid, from, continuation, now);
        pages++;
        for (const t of page.transactions) {
          const date = t.booking_date ?? t.value_date ?? t.transaction_date;
          if (t.status !== 'BOOK' || !date) {
            skipped++;
            continue;
          }
          booked++;
          const sign = t.credit_debit_indicator === 'DBIT' ? -1 : 1;
          const counterparty =
            (t.credit_debit_indicator === 'DBIT' ? t.creditor?.name : t.debtor?.name) ?? null;
          if (newest === null || date > newest) newest = date;
          let externalId: string;
          if (t.entry_reference) {
            externalId = `ref:${t.entry_reference}`;
          } else {
            const basis = transactionBasis(a.key, t);
            const occurrence = seen.get(basis) ?? 0;
            seen.set(basis, occurrence + 1);
            externalId = hashed(basis, occurrence);
          }
          transactions.push({
            accountExternalId: a.key,
            externalId,
            bookedOn: date,
            minor: sign * Math.abs(toMinor(t.transaction_amount.amount)),
            currency: t.transaction_amount.currency,
            description: describe(t.remittance_information, counterparty),
            counterparty,
          });
        }
        continuation = page.continuation_key ?? null;
      } while (continuation && pages < this.maxPages);
      this.log.info(
        {
          connection: connection.id,
          account: a.uid,
          from,
          pages,
          booked,
          skippedNotBooked: skipped,
          morePagesLeft: continuation !== null,
        },
        'dewidebug enable banking transactions read',
      );
      if (continuation === null) {
        syncedThrough[a.key] = today;
      } else {
        if (newest !== null) syncedThrough[a.key] = newest;
        warnings.push(
          `${a.name}: stopped after ${String(pages)} pages with more to read; the next sync carries on from ${syncedThrough[a.key] ?? from}`,
        );
      }
    }
    return {
      accounts,
      transactions,
      config: { syncedThrough },
      ...(warnings.length ? { warnings } : {}),
    };
  }
}
