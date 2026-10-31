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
  syncNow: (id: string) => request(`/api/connections/${enc(id)}/sync`, SyncStarted, json('POST')),
  transactions: (query: { account?: string; since?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (query.account) q.set('account', query.account);
    if (query.since) q.set('since', query.since);
    if (query.limit) q.set('limit', String(query.limit));
    return request(`/api/transactions?${q.toString()}`, TransactionsDto);
  },
};
