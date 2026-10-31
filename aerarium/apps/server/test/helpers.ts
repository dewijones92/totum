import { pino } from 'pino';
import { SecretBox } from '../src/secret-box.ts';
import { openMemoryDb } from '../src/store/db.ts';
import { wire } from '../src/wire.ts';
import type { Connector } from '../src/connectors/types.ts';
import type { EnableBankingClient } from '../src/connectors/enable-banking.ts';
import type { FetchLike } from '../src/http/read-only.ts';
import type { Notifier } from '../src/notify/notifier.ts';

export function testApp(
  opts: {
    ownerEmail?: string;
    ip?: string;
    fakeProviders?: boolean;
    connectors?: Connector[];
    enableBanking?: { client: EnableBankingClient; maxConsentDays: number };
    publicUrl?: string;
    monzoFetch?: FetchLike;
    notifier?: Notifier;
    now?: () => Date;
  } = {},
) {
  let current = new Date('2026-11-01T12:00:00Z');
  const clock = opts.now ?? (() => current);
  const w = wire({
    db: openMemoryDb(),
    secrets: SecretBox.ephemeral(),
    databaseFile: null,
    log: pino({ level: 'silent' }),
    version: 'test',
    trustedCidrs: ['172.21.0.0/16'],
    ownerEmail: opts.ownerEmail ?? '',
    fakeProviders: opts.fakeProviders ?? false,
    manualStaleDays: 90,
    ...(opts.connectors ? { connectors: opts.connectors } : {}),
    ...(opts.enableBanking ? { enableBanking: opts.enableBanking } : {}),
    ...(opts.publicUrl ? { publicUrl: opts.publicUrl } : {}),
    ...(opts.monzoFetch ? { monzoFetch: opts.monzoFetch } : {}),
    ...(opts.notifier ? { notifier: opts.notifier } : {}),
    clock,
    remoteAddress: () => opts.ip ?? '172.21.0.5',
  });
  const call = async (method: string, path: string, body?: unknown) => {
    const res = await w.app.request(path, {
      method,
      headers: { 'content-type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await res.text();
    return { status: res.status, body: text ? (JSON.parse(text) as unknown) : null };
  };
  return {
    ...w,
    call,
    advance: (ms: number) => {
      current = new Date(current.getTime() + ms);
    },
    clock,
  };
}
