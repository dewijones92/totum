import { readFileSync } from 'node:fs';
import type { Logger } from '../log.ts';
import type { FetchLike } from '../http/read-only.ts';

export interface Notifier {
  readonly kind: 'matrix' | 'log-only';
  send(text: string): Promise<boolean>;
}

export class LogOnlyNotifier implements Notifier {
  readonly kind = 'log-only' as const;
  readonly sent: string[] = [];
  constructor(private readonly log: Logger) {}
  send(text: string): Promise<boolean> {
    this.sent.push(text);
    this.log.info({ text }, 'dewidebug notification (log only: Matrix not configured)');
    return Promise.resolve(true);
  }
}

export function readAccessToken(credsFile: string): string | null {
  try {
    return /access_token:\s*(\S+)/.exec(readFileSync(credsFile, 'utf8'))?.[1] ?? null;
  } catch {
    return null;
  }
}

export class MatrixNotifier implements Notifier {
  readonly kind = 'matrix' as const;
  private readonly doFetch: FetchLike;

  constructor(
    private readonly o: {
      homeserver: string;
      room: string;
      credsFile: string;
      log: Logger;
      fetch?: FetchLike;
    },
  ) {
    this.doFetch = o.fetch ?? ((url, init) => fetch(url, init));
  }

  async send(text: string): Promise<boolean> {
    const token = readAccessToken(this.o.credsFile);
    if (!token) {
      this.o.log.warn(
        { credsFile: this.o.credsFile },
        'dewidebug matrix: no access token readable, not sent',
      );
      return false;
    }
    const url = `${this.o.homeserver.replace(/\/$/, '')}/_matrix/client/v3/rooms/${encodeURIComponent(this.o.room)}/send/m.room.message/aerarium-${String(Date.now())}-${String(Math.random()).slice(2, 8)}`;
    try {
      const res = await this.doFetch(url, {
        method: 'PUT',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ msgtype: 'm.text', body: text }),
        signal: AbortSignal.timeout(20_000),
      });
      this.o.log.info({ status: res.status, chars: text.length }, 'dewidebug matrix message sent');
      return res.ok;
    } catch (error) {
      this.o.log.error({ err: error }, 'dewidebug matrix message failed');
      return false;
    }
  }
}
