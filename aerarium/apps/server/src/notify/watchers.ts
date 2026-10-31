import { formatMoney, money, type HealthReport, type HealthState } from '@aerarium/shared';
import type { Logger } from '../log.ts';
import type { SettingsStore } from '../store/settings.ts';
import type { Notifier } from './notifier.ts';

const ICON: Record<HealthState, string> = { ok: '🟢', warn: '🟠', fail: '🔴' };

export interface HealthChange {
  key: string;
  label: string;
  from: HealthState | null;
  to: HealthState;
  reason: string;
}

export function healthStates(
  report: HealthReport,
): Map<string, { label: string; state: HealthState; reason: string }> {
  const m = new Map<string, { label: string; state: HealthState; reason: string }>();
  for (const s of report.sources) {
    const r = s.reasons[0];
    m.set(`source:${s.connectionId}`, {
      label: s.label,
      state: s.state,
      reason: r && 'error' in r ? r.error : (r?.code ?? ''),
    });
  }
  const app = report.app.reasons[0];
  m.set('app', { label: 'Aerarium itself', state: report.app.state, reason: app?.code ?? '' });
  return m;
}

export function diffHealth(
  previous: Record<string, HealthState>,
  current: Map<string, { label: string; state: HealthState; reason: string }>,
): HealthChange[] {
  const out: HealthChange[] = [];
  for (const [key, now] of current) {
    const before = previous[key] ?? null;
    if (before === now.state) continue;
    const loud = now.state === 'fail' || before === 'fail';
    if (!loud) continue;
    out.push({ key, label: now.label, from: before, to: now.state, reason: now.reason });
  }
  return out;
}

export function describeChange(c: HealthChange, publicUrl: string): string {
  if (c.to === 'fail') {
    return `${ICON.fail} Aerarium: ${c.label} is broken${c.reason ? ` (${c.reason})` : ''}. ${publicUrl}/health`;
  }
  return `${ICON[c.to]} Aerarium: ${c.label} is working again.`;
}

export class HealthWatcher {
  constructor(
    private readonly d: {
      settings: SettingsStore;
      notifier: Notifier;
      log: Logger;
      publicUrl: string;
    },
  ) {}

  async check(report: HealthReport): Promise<HealthChange[]> {
    const raw = this.d.settings.get('health-states');
    const previous = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<
      string,
      HealthState
    >;
    const current = healthStates(report);
    const changes = diffHealth(previous, current);
    const next: Record<string, HealthState> = {};
    for (const [k, v] of current) next[k] = v.state;
    this.d.settings.set('health-states', next);
    for (const c of changes) {
      this.d.log.info(c, 'dewidebug health changed, notifying');
      await this.d.notifier.send(describeChange(c, this.d.publicUrl));
    }
    return changes;
  }
}

export interface LargeTx {
  id: string;
  bookedOn: string;
  minor: number;
  currency: string;
  description: string;
  counterparty: string | null;
  accountName: string;
}

export function describeLarge(t: LargeTx): string {
  const verb = t.minor < 0 ? 'out of' : 'into';
  return `💷 Aerarium: ${formatMoney(money(Math.abs(t.minor), t.currency))} ${verb} ${t.accountName}: ${t.counterparty ?? t.description} (${t.bookedOn})`;
}
