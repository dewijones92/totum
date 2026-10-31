import type { Db } from './db.ts';

export interface NetWorthDay {
  day: string;
  household: number;
  yours: number;
}

export class NetWorthDaysStore {
  constructor(private readonly db: Db) {}

  record(day: string, household: number, yours: number, now: string): void {
    this.db
      .prepare(
        `INSERT INTO net_worth_days (day, household, yours, recorded_at) VALUES (?, ?, ?, ?)
         ON CONFLICT (day) DO UPDATE SET household = excluded.household, yours = excluded.yours,
           recorded_at = excluded.recorded_at`,
      )
      .run(day, household, yours, now);
  }

  on(day: string, toleranceDays = 3): { household: number } | null {
    const from = new Date(Date.parse(`${day}T00:00:00Z`) - toleranceDays * 86_400_000)
      .toISOString()
      .slice(0, 10);
    const row = this.db
      .prepare(
        'SELECT household FROM net_worth_days WHERE day <= ? AND day >= ? ORDER BY day DESC LIMIT 1',
      )
      .get(day, from) as { household: number } | undefined;
    return row ?? null;
  }

  since(day: string): NetWorthDay[] {
    return this.db
      .prepare('SELECT day, household, yours FROM net_worth_days WHERE day >= ? ORDER BY day')
      .all(day) as unknown as NetWorthDay[];
  }
}
