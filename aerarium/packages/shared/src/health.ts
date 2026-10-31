import { z } from 'zod';
import { IsoDate, IsoInstant, SourceKind, SyncRun } from './domain.ts';

export const Healthz = z.object({
  ok: z.boolean(),
  version: z.string(),
  schedulerLastTickAt: z.string().nullable(),
});
export type Healthz = z.infer<typeof Healthz>;

export const HealthState = z.enum(['ok', 'warn', 'fail']);
export type HealthState = z.infer<typeof HealthState>;

export const HealthReason = z.discriminatedUnion('code', [
  z.object({ code: z.literal('healthy') }),
  z.object({ code: z.literal('running') }),
  z.object({ code: z.literal('never-synced') }),
  z.object({ code: z.literal('last-run-failed'), error: z.string() }),
  z.object({ code: z.literal('stale'), hours: z.number() }),
  z.object({ code: z.literal('consent-expired'), at: IsoInstant }),
  z.object({ code: z.literal('consent-expiring'), days: z.number() }),
  z.object({ code: z.literal('manual-stale'), days: z.number() }),
  z.object({ code: z.literal('manual-missing') }),
  z.object({ code: z.literal('scheduler-silent'), minutes: z.number() }),
]);
export type HealthReason = z.infer<typeof HealthReason>;

export const SourceHealth = z.object({
  connectionId: z.string(),
  label: z.string(),
  kind: SourceKind,
  state: HealthState,
  reasons: z.array(HealthReason),
  lastOkAt: IsoInstant.nullable(),
  consentExpiresAt: IsoInstant.nullable(),
  lastRun: SyncRun.nullable(),
});
export type SourceHealth = z.infer<typeof SourceHealth>;

export const ManualHealth = z.object({
  accountId: z.string(),
  name: z.string(),
  state: HealthState,
  reasons: z.array(HealthReason),
  asOf: IsoDate.nullable(),
});
export type ManualHealth = z.infer<typeof ManualHealth>;

export const AppHealth = z.object({
  state: HealthState,
  reasons: z.array(HealthReason),
  version: z.string(),
  schedulerLastTickAt: IsoInstant.nullable(),
  databaseBytes: z.number().int(),
});
export type AppHealth = z.infer<typeof AppHealth>;

export const HealthReport = z.object({
  overall: HealthState,
  sources: z.array(SourceHealth),
  manual: z.array(ManualHealth),
  app: AppHealth,
});
export type HealthReport = z.infer<typeof HealthReport>;

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

export const worst = (states: readonly HealthState[]): HealthState =>
  states.includes('fail') ? 'fail' : states.includes('warn') ? 'warn' : 'ok';

export interface ConnectionFacts {
  lastRun: SyncRun | null;
  lastOkAt: string | null;
  consentExpiresAt: string | null;
  createdAt: string;
  everyHours: number;
}

export function judgeConnection(
  f: ConnectionFacts,
  now: Date,
): { state: HealthState; reasons: HealthReason[] } {
  const t = now.getTime();
  const reasons: { state: HealthState; reason: HealthReason }[] = [];
  if (f.consentExpiresAt) {
    const left = Date.parse(f.consentExpiresAt) - t;
    if (left <= 0) {
      reasons.push({ state: 'fail', reason: { code: 'consent-expired', at: f.consentExpiresAt } });
    } else if (left < 7 * DAY) {
      reasons.push({
        state: 'warn',
        reason: { code: 'consent-expiring', days: Math.ceil(left / DAY) },
      });
    }
  }
  const okAge = f.lastOkAt ? t - Date.parse(f.lastOkAt) : null;
  const tooOld = 2 * f.everyHours * HOUR;
  if (f.lastRun?.outcome === 'failed') {
    const recentOk = okAge !== null && okAge <= tooOld;
    reasons.push({
      state: recentOk ? 'warn' : 'fail',
      reason: { code: 'last-run-failed', error: f.lastRun.error ?? 'unknown error' },
    });
  }
  if (okAge === null) {
    const age = t - Date.parse(f.createdAt);
    if (f.lastRun?.outcome !== 'failed') {
      reasons.push({ state: age > tooOld ? 'fail' : 'warn', reason: { code: 'never-synced' } });
    }
  } else if (okAge > tooOld && f.lastRun?.outcome !== 'failed') {
    reasons.push({ state: 'fail', reason: { code: 'stale', hours: Math.round(okAge / HOUR) } });
  }
  if (f.lastRun?.outcome === 'running') {
    reasons.push({ state: 'ok', reason: { code: 'running' } });
  }
  const state = worst(reasons.map((r) => r.state));
  const shown = reasons.filter((r) => r.state !== 'ok' || r.reason.code === 'running');
  return {
    state,
    reasons: shown.length > 0 ? shown.map((r) => r.reason) : [{ code: 'healthy' }],
  };
}

export function judgeManual(
  asOf: string | null,
  now: Date,
  staleDays: number,
): { state: HealthState; reasons: HealthReason[] } {
  if (!asOf) return { state: 'warn', reasons: [{ code: 'manual-missing' }] };
  const days = Math.floor((now.getTime() - Date.parse(`${asOf}T00:00:00Z`)) / DAY);
  if (days > staleDays) return { state: 'warn', reasons: [{ code: 'manual-stale', days }] };
  return { state: 'ok', reasons: [{ code: 'healthy' }] };
}

export function judgeScheduler(
  lastTickAt: string | null,
  now: Date,
  silentAfterMinutes = 5,
): { state: HealthState; reasons: HealthReason[] } {
  const minutes =
    lastTickAt === null ? Infinity : (now.getTime() - Date.parse(lastTickAt)) / 60_000;
  if (minutes > silentAfterMinutes) {
    return {
      state: 'fail',
      reasons: [
        { code: 'scheduler-silent', minutes: Number.isFinite(minutes) ? Math.round(minutes) : -1 },
      ],
    };
  }
  return { state: 'ok', reasons: [{ code: 'healthy' }] };
}
