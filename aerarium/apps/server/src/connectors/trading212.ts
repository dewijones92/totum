import { z } from 'zod';
import { ReadOnlyHttp, type FetchLike } from '../http/read-only.ts';
import type { Logger } from '../log.ts';
import type { ConnectionRecord } from '../store/connections.ts';
import type { Connector, FetchedTransaction, SyncResult } from './types.ts';

const HOST = 'live.trading212.com';
const BASE = `https://${HOST}`;

export const TRADING212_ALLOWED = [
  { method: 'GET', host: HOST, path: /^\/api\/v0\/equity\/account\/summary$/ },
  { method: 'GET', host: HOST, path: /^\/api\/v0\/history\/transactions$/ },
] as const;

export const Trading212Secrets = z.object({ keyId: z.string().min(1), secret: z.string().min(1) });
export const Trading212Config = z.object({
  accountKind: z.enum(['isa-stocks', 'investment', 'pension']).default('isa-stocks'),
  accountName: z.string().default('Trading 212'),
});

const Summary = z.object({
  id: z.number(),
  currency: z.string(),
  totalValue: z.number(),
});

const TransactionsPage = z.object({
  items: z.array(
    z.object({
      type: z.string(),
      amount: z.number(),
      currency: z.string().optional(),
      reference: z.string(),
      dateTime: z.string(),
    }),
  ),
  nextPagePath: z.string().nullable().optional(),
});

const toMinor = (major: number) => Math.round(major * 100);

export class Trading212Connector implements Connector {
  readonly kind = 'trading212' as const;
  readonly everyHours = 6;
  readonly retryAfterHours = 1;
  private readonly http: ReadOnlyHttp;

  constructor(
    private readonly log: Logger,
    fetchImpl?: FetchLike,
    private readonly maxPages = 20,
  ) {
    this.http = new ReadOnlyHttp({
      provider: 'Trading 212',
      allowed: TRADING212_ALLOWED,
      log,
      ...(fetchImpl ? { fetch: fetchImpl } : {}),
    });
  }

  async sync(connection: ConnectionRecord, now: Date): Promise<SyncResult> {
    const secrets = Trading212Secrets.parse(connection.secrets);
    const config = Trading212Config.parse(connection.config);
    const headers = {
      authorization: `Basic ${Buffer.from(`${secrets.keyId}:${secrets.secret}`).toString('base64')}`,
    };
    const summary = Summary.parse(
      await this.http.json('GET', `${BASE}/api/v0/equity/account/summary`, { headers }),
    );
    const externalId = `t212-${String(summary.id)}`;
    const transactions: FetchedTransaction[] = [];
    let path: string | null = '/api/v0/history/transactions?limit=50';
    let pages = 0;
    while (path && pages < this.maxPages) {
      const page = TransactionsPage.parse(
        await this.http.json('GET', `${BASE}${path}`, { headers }),
      );
      pages++;
      for (const t of page.items) {
        const outflow = t.type === 'WITHDRAW' || t.type === 'FEE';
        transactions.push({
          accountExternalId: externalId,
          externalId: t.reference,
          bookedOn: t.dateTime.slice(0, 10),
          minor: outflow ? -Math.abs(toMinor(t.amount)) : toMinor(t.amount),
          currency: t.currency ?? summary.currency,
          description: t.type,
          counterparty: null,
        });
      }
      path = page.nextPagePath ?? null;
    }
    this.log.info(
      {
        connection: connection.id,
        pages,
        transactions: transactions.length,
        morePagesLeft: path !== null,
      },
      'dewidebug trading212 history read',
    );
    return {
      accounts: [
        {
          externalId,
          name: config.accountName,
          kind: config.accountKind,
          currency: summary.currency,
          balance: { minor: toMinor(summary.totalValue), asOf: now.toISOString().slice(0, 10) },
        },
      ],
      transactions,
    };
  }
}
