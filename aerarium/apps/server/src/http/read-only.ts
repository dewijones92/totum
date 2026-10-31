import type { Logger } from '../log.ts';

export type Method = 'GET' | 'POST';

export interface Allowed {
  method: Method;
  host: string;
  path: RegExp;
}

export class ReadOnlyViolation extends Error {
  constructor(method: string, url: string) {
    super(`Refused ${method} ${url}: not on this connector's read-only list`);
    this.name = 'ReadOnlyViolation';
  }
}

export class ProviderError extends Error {
  constructor(
    readonly status: number,
    readonly provider: string,
    detail: string,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(`${provider} answered HTTP ${String(status)}${detail ? `: ${detail}` : ''}`);
    this.name = 'ProviderError';
  }
}

export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

export interface ReadOnlyHttpOptions {
  provider: string;
  allowed: readonly Allowed[];
  log: Logger;
  fetch?: FetchLike;
  timeoutMs?: number;
}

export class ReadOnlyHttp {
  private readonly doFetch: FetchLike;

  constructor(private readonly o: ReadOnlyHttpOptions) {
    this.doFetch = o.fetch ?? ((url, init) => fetch(url, init));
  }

  isAllowed(method: Method, url: string): boolean {
    const u = new URL(url);
    return this.o.allowed.some(
      (a) => a.method === method && a.host === u.host && a.path.test(u.pathname),
    );
  }

  async json(
    method: Method,
    url: string,
    init: { headers?: Record<string, string>; body?: string } = {},
  ): Promise<unknown> {
    if (!this.isAllowed(method, url)) {
      this.o.log.error(
        { provider: this.o.provider, method, url },
        'dewidebug refused a non-read-only call',
      );
      throw new ReadOnlyViolation(method, url);
    }
    const path = new URL(url).pathname;
    const started = performance.now();
    let res: Response;
    try {
      res = await this.doFetch(url, {
        method,
        headers: init.headers ?? {},
        ...(init.body === undefined ? {} : { body: init.body }),
        signal: AbortSignal.timeout(this.o.timeoutMs ?? 30_000),
      });
    } catch (error) {
      this.o.log.error(
        {
          provider: this.o.provider,
          method,
          path,
          err: error,
          ms: Math.round(performance.now() - started),
        },
        'dewidebug provider call did not complete',
      );
      throw new Error(
        `${this.o.provider} could not be reached: ${error instanceof Error ? error.message : String(error)}`,
        { cause: error },
      );
    }
    const ms = Math.round(performance.now() - started);
    const text = await res.text();
    this.o.log.info(
      { provider: this.o.provider, method, path, status: res.status, bytes: text.length, ms },
      'dewidebug provider call',
    );
    if (!res.ok) {
      const retry = res.headers.get('retry-after');
      throw new ProviderError(
        res.status,
        this.o.provider,
        text.slice(0, 200),
        retry ? Number(retry) || null : null,
      );
    }
    return text === '' ? null : (JSON.parse(text) as unknown);
  }
}
