import { lookup } from 'node:dns/promises';
import type { Logger } from '../log.ts';
import { isTrusted } from './ip.ts';

export type Resolve = (host: string) => Promise<string[]>;

const defaultResolve: Resolve = async (host) =>
  (await lookup(host, { all: true })).map((r) => r.address);

const normalise = (ip: string) => (ip.toLowerCase().startsWith('::ffff:') ? ip.slice(7) : ip);

export class TrustedPeers {
  private resolved = new Set<string>();
  private resolvedAt = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly o: {
      cidrs: readonly string[];
      hosts: readonly string[];
      log: Logger;
      resolve?: Resolve;
      ttlMs?: number;
      retryMs?: number;
      clock?: () => number;
    },
  ) {}

  private now(): number {
    return this.o.clock ? this.o.clock() : Date.now();
  }

  private async refresh(reason: string): Promise<void> {
    const resolve = this.o.resolve ?? defaultResolve;
    const next = new Set<string>();
    for (const host of this.o.hosts) {
      try {
        for (const ip of await resolve(host)) next.add(normalise(ip));
      } catch (error) {
        this.o.log.warn(
          { host, err: error },
          'dewidebug trusted host did not resolve; refusing it until it does',
        );
      }
    }
    const changed = [...next].sort().join(',') !== [...this.resolved].sort().join(',');
    this.resolved = next;
    this.resolvedAt = this.now();
    if (changed)
      this.o.log.info(
        { hosts: this.o.hosts, addresses: [...next], reason },
        'dewidebug trusted hosts resolved',
      );
  }

  async allows(ip: string | undefined): Promise<boolean> {
    if (!ip) return false;
    if (isTrusted(ip, this.o.cidrs)) return true;
    if (this.o.hosts.length === 0) return false;
    const age = this.now() - this.resolvedAt;
    if (age > (this.o.ttlMs ?? 30_000)) await this.refresh('expired');
    if (this.resolved.has(normalise(ip))) return true;
    if (age > (this.o.retryMs ?? 2_000)) {
      await this.refresh('unknown address, checking whether a trusted host moved');
      return this.resolved.has(normalise(ip));
    }
    return false;
  }
}
