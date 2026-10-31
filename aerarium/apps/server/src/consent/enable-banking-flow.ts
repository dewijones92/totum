import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import {
  accountName,
  EnableBankingConfig,
  kindFromCashAccountType,
  type EnableBankingClient,
} from '../connectors/enable-banking.ts';
import type { Logger } from '../log.ts';
import type { ConnectionsStore } from '../store/connections.ts';
import type { SettingsStore } from '../store/settings.ts';

const Pending = z.object({ name: z.string(), country: z.string(), createdAt: z.string() });
const STATE_PREFIX = 'eb-state:';
const DAY = 86_400_000;

export interface FlowDeps {
  client: EnableBankingClient;
  connections: ConnectionsStore;
  settings: SettingsStore;
  log: Logger;
  maxConsentDays: number;
}

export type CallbackOutcome =
  | { kind: 'connected'; connectionId: string; label: string; renewed: boolean; accounts: number }
  | { kind: 'refused'; reason: string };

export interface CallbackQuery {
  code?: string | undefined;
  state?: string | undefined;
  error?: string | undefined;
  error_description?: string | undefined;
}

export class EnableBankingFlow {
  constructor(private readonly d: FlowDeps) {}

  banks(country: string, now: Date) {
    return this.d.client.banks(country, now);
  }

  async start(aspsp: { name: string; country: string }, now: Date): Promise<{ url: string }> {
    const banks = await this.d.client.banks(aspsp.country, now);
    const bank = banks.find((b) => b.name === aspsp.name);
    if (!bank) throw new Error(`Open Banking does not list ${aspsp.name} in ${aspsp.country}`);
    const bankDays = Math.floor(
      (bank.maximum_consent_validity ?? this.d.maxConsentDays * 86_400) / 86_400,
    );
    const days = Math.min(this.d.maxConsentDays, bankDays);
    const state = randomBytes(18).toString('base64url');
    this.d.settings.set(`${STATE_PREFIX}${state}`, { ...aspsp, createdAt: now.toISOString() });
    this.d.log.info({ aspsp: aspsp.name, days }, 'dewidebug open banking consent starting');
    return this.d.client.startAuth(aspsp, new Date(now.getTime() + days * DAY), state, now);
  }

  private forgetStaleStates(now: Date): void {
    for (const key of this.d.settings.keysWithPrefix(STATE_PREFIX)) {
      const p = Pending.safeParse(this.d.settings.get(key));
      if (!p.success || now.getTime() - Date.parse(p.data.createdAt) > DAY) {
        this.d.settings.delete(key);
      }
    }
  }

  async callback(q: CallbackQuery, now: Date): Promise<CallbackOutcome> {
    const key = `${STATE_PREFIX}${q.state ?? ''}`;
    const pending = Pending.safeParse(this.d.settings.get(key));
    this.d.settings.delete(key);
    this.forgetStaleStates(now);
    if (!pending.success) {
      this.d.log.warn(
        { hasState: Boolean(q.state) },
        'dewidebug open banking callback with an unknown state',
      );
      return {
        kind: 'refused',
        reason: 'This sign-in link was not started here or has expired. Start again.',
      };
    }
    if (q.error || !q.code) {
      this.d.log.warn(
        { error: q.error, aspsp: pending.data.name },
        'dewidebug open banking consent declined',
      );
      return {
        kind: 'refused',
        reason: q.error_description ?? q.error ?? 'The bank did not return a consent.',
      };
    }
    const session = await this.d.client.createSession(q.code, now);
    const accounts = session.accounts.map((a) => ({
      uid: a.uid,
      name: accountName(a, session.aspsp.name),
      kind: kindFromCashAccountType(a.cash_account_type),
      currency: a.currency ?? 'GBP',
    }));
    const config = {
      aspspName: session.aspsp.name,
      aspspCountry: session.aspsp.country,
      accounts,
      syncedThrough: {},
    };
    const existing = this.d.connections.list().find((c) => {
      if (c.kind !== 'enable-banking') return false;
      const cfg = EnableBankingConfig.safeParse(c.config);
      return (
        cfg.success &&
        cfg.data.aspspName === session.aspsp.name &&
        cfg.data.aspspCountry === session.aspsp.country
      );
    });
    if (existing) {
      const old = EnableBankingConfig.parse(existing.config);
      this.d.connections.update(existing.id, {
        consentExpiresAt: session.access.valid_until,
        config: { ...config, syncedThrough: old.syncedThrough },
        secrets: { sessionId: session.session_id },
      });
      this.d.log.info(
        { id: existing.id, aspsp: session.aspsp.name, accounts: accounts.length },
        'dewidebug open banking consent renewed',
      );
      return {
        kind: 'connected',
        connectionId: existing.id,
        label: existing.label,
        renewed: true,
        accounts: accounts.length,
      };
    }
    const id = this.d.connections.create(
      {
        kind: 'enable-banking',
        label: session.aspsp.name,
        config,
        secrets: { sessionId: session.session_id },
        consentExpiresAt: session.access.valid_until,
      },
      now.toISOString(),
    );
    this.d.log.info(
      { id, aspsp: session.aspsp.name, accounts: accounts.length },
      'dewidebug open banking connected',
    );
    return {
      kind: 'connected',
      connectionId: id,
      label: session.aspsp.name,
      renewed: false,
      accounts: accounts.length,
    };
  }
}
