import { WeeklyReport } from '@aerarium/shared';
import type { Db } from './db.ts';

export class ReportsStore {
  constructor(private readonly db: Db) {}

  has(weekEnd: string): boolean {
    return (
      this.db.prepare('SELECT 1 FROM weekly_reports WHERE week_end = ?').get(weekEnd) !== undefined
    );
  }

  save(r: WeeklyReport, now: string): void {
    this.db
      .prepare(
        `INSERT INTO weekly_reports (week_end, created_at, report_json) VALUES (?, ?, ?)
         ON CONFLICT (week_end) DO UPDATE SET created_at = excluded.created_at, report_json = excluded.report_json`,
      )
      .run(r.weekEnd, now, JSON.stringify(r));
  }

  list(limit: number): WeeklyReport[] {
    return (
      this.db
        .prepare('SELECT report_json FROM weekly_reports ORDER BY week_end DESC LIMIT ?')
        .all(limit) as unknown as { report_json: string }[]
    ).map((r) => WeeklyReport.parse(JSON.parse(r.report_json)));
  }
}
