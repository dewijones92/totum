import { z } from 'zod';
import { ReadOnlyHttp, ProviderError, type Allowed, type FetchLike } from '../http/read-only.ts';
import type { Logger } from '../log.ts';
import type { ConnectionRecord } from '../store/connections.ts';
import type { Connector, FetchedAccount, FetchedTransaction, SyncResult } from './types.ts';

const API = 'api.monzo.com';

export const MONZO_ALLOWED: readonly Allowed[] = [
  { method: 'POST', host: API, path: /^\/oauth2\/token$/ },
  { method: 'GET', host: API, path: /^\/accounts$/ },
  { method: 'GET', host: API, path: /^\/balance$/ },
  { method: 'GET', host: API, path: /^\/pots$/ },
  { method: 'GET', host: API, path: /^\/transactions$/ },
];

export const MonzoClientCredentials = z.object({
  clientId: z.string().min(1),
  clientSecret: z.string().min(1),
  redirectUrl: z.url(),
});
export type MonzoClientCredentials = z.infer<typeof MonzoClientCredentials>;

export const MonzoSecrets = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  expiresAt: z.string(),
});
export type MonzoSecrets = z.infer<typeof MonzoSecrets>;

const Token = z.object({
  access_token: z.string(),
  refresh_token: z.string(),
  expires_in: z.number(),
});

const Accounts = z.object({
  accounts: z.array(
    z.object({
      id: z.string(),
      description: z.string().optional(),
      type: z.string().optional(),
      closed: z.boolean().optional(),
      currency: z.string().optional(),
    }),
  ),
});
const Balance = z.object({ balance: z.number(), currency: z.string() });
const Pots = z.object({
  pots: z.array(
    z.object({
      id: z.string(),
      name: z.string(),
      balance: z.number(),
      currency: z.string(),
      deleted: z.boolean().optional(),
    }),
  ),
});
const Transactions = z.object({
  transactions: z.array(
    z.object({
      id: z.string(),
      created: z.string(),
      amount: z.number(),
      currency: z.string(),
      description: z.string().optional(),
      merchant: z
        .union([z.string(), z.object({ name: z.string().optional() }), z.null()])
        .optional(),
      counterparty: z.object({ name: z.string().optional() }).partial().optional(),
      decline_reason: z.string().optional(),
    }),
  ),
});

export class MonzoApi {
  readonly http: ReadOnlyHttp;

  constructor(
    private readonly creds: () => MonzoClientCredentials | null,
    log: Logger,
    fetchImpl?: FetchLike,
  ) {
    this.http = new ReadOnlyHttp({
      provider: 'Monzo',
      allowed: MONZO_ALLOWED,
      log,
      ...(fetchImpl ? { fetch: fetchImpl } : {}),
    });
  }

  credentials(): MonzoClientCredentials {
    const c = this.creds();
    if (!c)
      throw new Error('Monzo is not set up: add the developer client ID and secret on Connections');
    return c;
  }

  authUrl(state: string): string {
    const c = this.credentials();
    const q = new URLSearchParams({
      client_id: c.clientId,
      redirect_uri: c.redirectUrl,
      response_type: 'code',
      state,
    });
    return `https://auth.monzo.com/?${q.toString()}`;
  }

  private async token(form: Record<string, string>, now: Date): Promise<MonzoSecrets> {
    const c = this.credentials();
    const body = new URLSearchParams({
      client_id: c.clientId,
      client_secret: c.clientSecret,
      ...form,
    });
    const t = Token.parse(
      await this.http.json('POST', `https://${API}/oauth2/token`, {
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
      }),
    );
    return {
      accessToken: t.access_token,
      refreshToken: t.refresh_token,
      expiresAt: new Date(now.getTime() + t.expires_in * 1000).toISOString(),
    };
  }

  exchange(code: string, now: Date): Promise<MonzoSecrets> {
    return this.token(
      { grant_type: 'authorization_code', redirect_uri: this.credentials().redirectUrl, code },
      now,
    );
  }

  refresh(refreshToken: string, now: Date): Promise<MonzoSecrets> {
    return this.token({ grant_type: 'refresh_token', refresh_token: refreshToken }, now);
  }

  async get<S extends z.ZodType>(path: string, token: string, schema: S): Promise<z.output<S>> {
    return schema.parse(
      await this.http.json('GET', `https://${API}${path}`, {
        headers: { authorization: `Bearer ${token}` },
      }),
    );
  }
}

const isoDay = (iso: string) => iso.slice(0, 10);

export class MonzoConnector implements Connector {
  readonly kind = 'monzo' as const;
  readonly everyHours = 6;
  readonly retryAfterHours = 1;

  constructor(
    private readonly api: MonzoApi,
    private readonly log: Logger,
    private readonly maxPages = 100,
  ) {}

  async sync(connection: ConnectionRecord, now: Date): Promise<SyncResult> {
    let secrets = MonzoSecrets.parse(connection.secrets);
    let refreshed = false;
    if (Date.parse(secrets.expiresAt) - now.getTime() < 5 * 60_000) {
      secrets = await this.api.refresh(secrets.refreshToken, now);
      refreshed = true;
      this.log.info({ connection: connection.id }, 'dewidebug monzo token refreshed before sync');
    }
    const token = secrets.accessToken;
    let list;
    try {
      list = await this.api.get('/accounts', token, Accounts);
    } catch (error) {
      if (error instanceof ProviderError && error.status === 403) {
        throw new Error(
          'Monzo refused access: approve Aerarium in the Monzo app (it asks once after connecting), then press Sync now',
          { cause: error },
        );
      }
      throw error;
    }
    const accounts: FetchedAccount[] = [];
    const transactions: FetchedTransaction[] = [];
    const syncedThrough = z
      .record(z.string(), z.string())
      .catch({})
      .parse(connection.config['syncedThrough']);
    const today = isoDay(now.toISOString());
    for (const a of list.accounts.filter((x) => !x.closed)) {
      const bal = await this.api.get(
        `/balance?account_id=${encodeURIComponent(a.id)}`,
        token,
        Balance,
      );
      accounts.push({
        externalId: a.id,
        name: a.description ?? 'Monzo account',
        kind: 'current',
        currency: bal.currency,
        balance: { minor: bal.balance, asOf: today },
      });
      const pots = await this.api.get(
        `/pots?current_account_id=${encodeURIComponent(a.id)}`,
        token,
        Pots,
      );
      for (const p of pots.pots.filter((x) => !x.deleted)) {
        accounts.push({
          externalId: p.id,
          name: `${p.name} (pot)`,
          kind: 'savings',
          currency: p.currency,
          balance: { minor: p.balance, asOf: today },
        });
      }
      let since =
        syncedThrough[a.id] ?? new Date(now.getTime() - 3 * 365 * 86_400_000).toISOString();
      let pages = 0;
      let declined = 0;
      for (;;) {
        const q = new URLSearchParams({
          account_id: a.id,
          since,
          limit: '100',
          'expand[]': 'merchant',
        });
        const page = await this.api.get(`/transactions?${q.toString()}`, token, Transactions);
        pages++;
        for (const t of page.transactions) {
          if (t.decline_reason) {
            declined++;
            continue;
          }
          const merchant =
            typeof t.merchant === 'object' && t.merchant ? t.merchant.name : undefined;
          transactions.push({
            accountExternalId: a.id,
            externalId: t.id,
            bookedOn: isoDay(t.created),
            minor: t.amount,
            currency: t.currency,
            description: t.description ?? merchant ?? '—',
            counterparty: merchant ?? t.counterparty?.name ?? null,
          });
        }
        const last = page.transactions.at(-1);
        if (page.transactions.length < 100 || !last || pages >= this.maxPages) break;
        since = last.id;
      }
      syncedThrough[a.id] = new Date(now.getTime() - 10 * 86_400_000).toISOString();
      this.log.info(
        {
          connection: connection.id,
          account: a.id,
          pots: pots.pots.length,
          pages,
          declinedSkipped: declined,
        },
        'dewidebug monzo account read',
      );
    }
    return {
      accounts,
      transactions,
      config: { syncedThrough },
      ...(refreshed ? { secrets } : {}),
    };
  }
}
