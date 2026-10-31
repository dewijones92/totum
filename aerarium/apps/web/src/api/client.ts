import {
  Account,
  ConnectionsDto,
  Dashboard,
  HealthReport,
  SyncRunsDto,
  SyncStarted,
  TransactionsDto,
  type AccountPatch,
  type ManualBalance,
  type NewManualAccount,
  type NewTrading212,
} from '@aerarium/shared';
import { z } from 'zod';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const ErrorBody = z.object({ error: z.string() });

async function request<S extends z.ZodType>(
  path: string,
  schema: S,
  init: RequestInit = {},
): Promise<z.output<S>> {
  const res = await fetch(path, { credentials: 'same-origin', ...init });
  if (!res.ok) {
    const parsed = ErrorBody.safeParse(await res.json().catch(() => null));
    const message = parsed.success ? parsed.data.error : `${String(res.status)} ${res.statusText}`;
    console.info('dewidebug api error', { path, status: res.status, message });
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return schema.parse(undefined);
  return schema.parse(await res.json());
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: { 'content-type': 'application/json' },
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
});

const enc = encodeURIComponent;

export const api = {
  dashboard: () => request('/api/dashboard', Dashboard),
  accounts: () => request('/api/accounts', z.array(Account)),
  addManual: (input: NewManualAccount) =>
    request('/api/accounts/manual', Account, json('POST', input)),
  patchAccount: (id: string, patch: AccountPatch) =>
    request(`/api/accounts/${enc(id)}`, Account, json('PATCH', patch)),
  recordBalance: (id: string, input: ManualBalance) =>
    request(`/api/accounts/${enc(id)}/balance`, Account, json('POST', input)),
  deleteAccount: (id: string) => request(`/api/accounts/${enc(id)}`, z.undefined(), json('DELETE')),
  health: () => request('/api/health', HealthReport),
  connections: () => request('/api/connections', ConnectionsDto),
  runs: (id: string) => request(`/api/connections/${enc(id)}/runs`, SyncRunsDto),
  connectTrading212: (input: NewTrading212) =>
    request('/api/connections/trading212', z.object({ id: z.string() }), json('POST', input)),
  disconnect: (id: string) => request(`/api/connections/${enc(id)}`, z.undefined(), json('DELETE')),
  ebStatus: () => request('/api/eb/status', z.object({ configured: z.boolean() })),
  ebBanks: (country = 'GB') =>
    request(
      `/api/eb/banks?country=${enc(country)}`,
      z.array(
        z.object({ name: z.string(), country: z.string(), maxConsentDays: z.number().nullable() }),
      ),
    ),
  ebStart: (name: string, country = 'GB') =>
    request('/api/eb/start', z.object({ url: z.string() }), json('POST', { name, country })),
  reconnect: (id: string) =>
    request(`/api/connections/${enc(id)}/reconnect`, z.object({ url: z.string() }), json('POST')),
  monzoStatus: () =>
    request(
      '/api/monzo/status',
      z.object({
        available: z.boolean(),
        configured: z.boolean(),
        redirectUrl: z.string().nullable(),
      }),
    ),
  monzoCredentials: (clientId: string, clientSecret: string) =>
    request(
      '/api/monzo/credentials',
      z.object({ configured: z.boolean() }),
      json('POST', { clientId, clientSecret }),
    ),
  monzoStart: () => request('/api/monzo/start', z.object({ url: z.string() }), json('POST')),
  syncNow: (id: string) => request(`/api/connections/${enc(id)}/sync`, SyncStarted, json('POST')),
  transactions: (query: { account?: string; since?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (query.account) q.set('account', query.account);
    if (query.since) q.set('since', query.since);
    if (query.limit) q.set('limit', String(query.limit));
    return request(`/api/transactions?${q.toString()}`, TransactionsDto);
  },
};
