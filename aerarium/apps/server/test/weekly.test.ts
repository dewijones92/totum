import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import type { WeeklyReport } from '@aerarium/shared';
import { LogOnlyNotifier } from '../src/notify/notifier.ts';
import { headline } from '../src/notify/weekly.ts';
import { testApp } from './helpers.ts';

const HOUR = 3_600_000;

describe('weekly report job', () => {
  it('makes last week’s report once on Monday morning, and on demand any day', async () => {
    const notifier = new LogOnlyNotifier(pino({ level: 'silent' }));
    const t = testApp({ fakeProviders: true, notifier, publicUrl: 'https://aerarium.example.com' });
    await t.call('POST', '/api/dev/fake/connect');
    await t.scheduler.tick();
    expect(notifier.sent.filter((m) => m.startsWith('📊'))).toHaveLength(0);

    const forced = await t.call('POST', '/api/reports/run');
    expect(forced.status).toBe(201);
    expect(forced.body).toMatchObject({ weekStart: '2026-10-19', weekEnd: '2026-10-25' });

    expect(notifier.sent.filter((m) => m.startsWith('📊'))).toHaveLength(0);

    t.advance(24 * HOUR - 5 * HOUR);
    await t.scheduler.tick();
    const reports = (await t.call('GET', '/api/reports')).body as WeeklyReport[];
    expect(reports.map((r) => r.weekEnd)).toEqual(['2026-11-01', '2026-10-25']);
    const sent = notifier.sent.filter((m) => m.startsWith('📊'));
    expect(sent).toHaveLength(1);
    expect(sent[0]).toContain('week to 2026-11-01');
    expect(sent[0]).toContain('https://aerarium.example.com/reports');
    t.advance(3 * HOUR);
    await t.scheduler.tick();
    expect(notifier.sent.filter((m) => m.startsWith('📊'))).toHaveLength(1);
  });
});

describe('headline', () => {
  const base: WeeklyReport = {
    weekStart: '2026-10-26',
    weekEnd: '2026-11-01',
    netWorthStart: 100,
    netWorthEnd: 50,
    netWorthChange: -12_345,
    income: 0,
    spending: 20_000,
    saved: -20_000,
    interest: 150,
    markets: 7_500,
    debtPaidDown: 0,
    unexplained: 5,
    usualWeeklySpending: 18_000,
    topCategories: [],
    yearToDateChange: null,
    projection12Months: null,
    savingsRatePercent: null,
    taxYearStart: '2026-04-06',
    interestOutsideIsaThisTaxYear: 0,
    personalSavingsAllowance: 100_000,
  };
  it('says the direction of every move plainly', () => {
    expect(headline(base, 'https://a.example.com')).toBe(
      '📊 Aerarium week to 2026-11-01: net worth −£123.45. Saved −£200.00, markets +£75.00, interest £1.50. Spent £200.00 (usual £180.00). https://a.example.com/reports',
    );
  });
});
