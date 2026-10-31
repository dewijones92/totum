import { randomUUID } from 'node:crypto';
import {
  formatMoney,
  IsaSubscription,
  isaAllowance,
  KeyDate,
  money,
  reminders,
  type NewIsaSubscription,
  type NewKeyDate,
  type Reminder,
} from '@aerarium/shared';
import { z } from 'zod';
import type { Logger } from '../log.ts';
import type { AccountsStore } from '../store/accounts.ts';
import type { ConnectionsStore } from '../store/connections.ts';
import type { SettingsStore } from '../store/settings.ts';
import type { TransactionsStore } from '../store/transactions.ts';
import type { Notifier } from './notifier.ts';

const KEY_DATES = 'key-dates';
const ISA_MANUAL = 'isa-manual';
const REMINDED = 'reminded';

export interface NudgeDeps {
  accounts: AccountsStore;
  connections: ConnectionsStore;
  transactions: TransactionsStore;
  settings: SettingsStore;
  notifier: Notifier;
  log: Logger;
  publicUrl: string;
  idleThresholdMinor: number;
}

export class Nudges {
  constructor(private readonly d: NudgeDeps) {}

  keyDates(): KeyDate[] {
    return z.array(KeyDate).catch([]).parse(this.d.settings.get(KEY_DATES));
  }

  addKeyDate(input: NewKeyDate): KeyDate {
    const k = { ...input, id: randomUUID() };
    this.d.settings.set(
      KEY_DATES,
      [...this.keyDates(), k].sort((a, b) => a.date.localeCompare(b.date)),
    );
    this.d.log.info({ kind: k.kind, date: k.date }, 'dewidebug key date added');
    return k;
  }

  removeKeyDate(id: string): boolean {
    const before = this.keyDates();
    const after = before.filter((k) => k.id !== id);
    this.d.settings.set(KEY_DATES, after);
    return after.length < before.length;
  }

  isaManual(): IsaSubscription[] {
    return z.array(IsaSubscription).catch([]).parse(this.d.settings.get(ISA_MANUAL));
  }

  addIsa(input: NewIsaSubscription): IsaSubscription {
    const s = { ...input, id: randomUUID() };
    this.d.settings.set(ISA_MANUAL, [...this.isaManual(), s]);
    this.d.log.info({ date: s.date }, 'dewidebug manual ISA subscription added');
    return s;
  }

  removeIsa(id: string): boolean {
    const before = this.isaManual();
    const after = before.filter((s) => s.id !== id);
    this.d.settings.set(ISA_MANUAL, after);
    return after.length < before.length;
  }

  compute(now: Date) {
    const today = now.toISOString().slice(0, 10);
    const accounts = this.d.accounts.list();
    const since = new Date(now.getTime() - 400 * 86_400_000).toISOString().slice(0, 10);
    const transactions = this.d.transactions.list({ since, limit: 50_000 });
    return {
      reminders: reminders({
        today,
        keyDates: this.keyDates(),
        connections: this.d.connections.synced(),
        currentAccounts: accounts
          .filter((a) => a.kind === 'current' && a.includeInNetWorth)
          .map((a) => ({ id: a.id, name: a.name, history: this.d.accounts.balanceHistory(a.id) })),
        idleThresholdMinor: this.d.idleThresholdMinor,
        idleDays: 30,
      }),
      isa: isaAllowance({ today, accounts, transactions, manual: this.isaManual() }),
    };
  }

  private remindKey(r: Reminder, now: Date): string {
    switch (r.kind) {
      case 'key-date':
        return `key:${r.id}:${r.date}`;
      case 'consent':
        return `consent:${r.connectionId}:${r.date}`;
      case 'idle-cash':
        return `idle:${r.accountId}:${now.toISOString().slice(0, 7)}`;
    }
  }

  describe(r: Reminder): string {
    switch (r.kind) {
      case 'key-date':
        return `⏰ Aerarium: ${r.title} in ${String(r.daysLeft)} day${r.daysLeft === 1 ? '' : 's'} (${r.date}). ${this.d.publicUrl}/reminders`;
      case 'consent':
        return `🔑 Aerarium: ${r.label} bank consent ends ${r.date}. Reconnect: ${this.d.publicUrl}/health`;
      case 'idle-cash':
        return `💤 Aerarium: at least ${formatMoney(money(r.minMinor))} has sat in ${r.name} for ${String(r.days)} days earning nothing.`;
    }
  }

  async notifyNew(now: Date): Promise<number> {
    const done = new Set(z.array(z.string()).catch([]).parse(this.d.settings.get(REMINDED)));
    let sent = 0;
    for (const r of this.compute(now).reminders) {
      const key = this.remindKey(r, now);
      if (done.has(key)) continue;
      if (await this.d.notifier.send(this.describe(r))) {
        done.add(key);
        sent++;
      } else {
        this.d.log.warn({ key }, 'dewidebug reminder not delivered, will retry next tick');
      }
    }
    if (sent > 0) this.d.settings.set(REMINDED, [...done].slice(-500));
    return sent;
  }
}
