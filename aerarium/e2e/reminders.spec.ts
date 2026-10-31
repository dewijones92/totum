import { expect, test } from '@playwright/test';
import { strings } from '../apps/web/src/strings.ts';

test('a key date shows as coming up, and a typed-in ISA payment counts toward the allowance', async ({
  page,
}) => {
  const soon = new Date(Date.now() + 10 * 86_400_000).toISOString().slice(0, 10);
  await page.goto('/reminders');
  await page.getByLabel(strings.reminders.dateTitle).fill('E2E policy renews');
  await page.getByLabel(strings.reminders.dateKind).selectOption('renewal');
  await page.getByLabel(strings.reminders.dateWhen).fill(soon);
  await page.getByRole('button', { name: strings.reminders.add }).last().click();
  await expect(page.getByTestId('reminder').filter({ hasText: 'E2E policy renews' })).toContainText(
    'in 10 days',
  );

  const isa = page.getByTestId('isa');
  const before = (await isa.textContent()) ?? '';
  await isa.getByLabel(strings.reminders.isaAmount).fill('1500');
  await isa.getByRole('button', { name: strings.reminders.add }).click();
  await expect(isa).toContainText('£1,500.00');
  await expect.poll(async () => (await isa.textContent()) !== before).toBe(true);

  await page.getByRole('link', { name: strings.nav.dashboard }).click();
  await expect(page.getByTestId('reminder').filter({ hasText: 'E2E policy renews' })).toBeVisible();
});
