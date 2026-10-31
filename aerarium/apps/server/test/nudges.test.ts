import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import type { IsaAllowance, KeyDate, Reminder } from '@aerarium/shared';
import { LogOnlyNotifier } from '../src/notify/notifier.ts';
import { testApp } from './helpers.ts';

describe('reminders and the ISA allowance over the API', () => {
  it('reminds once about a key date, counts a typed-in ISA payment, and deletes both', async () => {
    const notifier = new LogOnlyNotifier(pino({ level: 'silent' }));
    const t = testApp({ notifier, publicUrl: 'https://aerarium.example.com' });
    const added = await t.call('POST', '/api/key-dates', {
      title: 'Home insurance renews',
      kind: 'renewal',
      date: '2026-11-20',
    });
    expect(added.status).toBe(201);
    const id = (added.body as KeyDate).id;
    const nudges = (await t.call('GET', '/api/nudges')).body as {
      reminders: Reminder[];
      isa: IsaAllowance;
    };
    expect(nudges.reminders).toMatchObject([
      { kind: 'key-date', title: 'Home insurance renews', daysLeft: 19 },
    ]);

    await t.scheduler.tick();
    await t.scheduler.tick();
    expect(notifier.sent.filter((m) => m.startsWith('⏰'))).toEqual([
      '⏰ Aerarium: Home insurance renews in 19 days (2026-11-20). https://aerarium.example.com/reminders',
    ]);

    const isa = await t.call('POST', '/api/isa-subscriptions', {
      date: '2026-06-01',
      amountMinor: 400_000,
      note: 'typed-in cash ISA',
    });
    expect(isa.status).toBe(201);
    const after = (await t.call('GET', '/api/nudges')).body as { isa: IsaAllowance };
    expect(after.isa).toMatchObject({ usedMinor: 400_000, leftMinor: 1_600_000 });

    expect((await t.call('DELETE', `/api/key-dates/${id}`)).status).toBe(204);
    expect((await t.call('DELETE', `/api/key-dates/${id}`)).status).toBe(404);
    expect(
      (await t.call('POST', '/api/key-dates', { title: '', kind: 'renewal', date: '2026-11-20' }))
        .status,
    ).toBe(400);
  });
});
