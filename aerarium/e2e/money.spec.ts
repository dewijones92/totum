import { expect, test } from '@playwright/test';
import { strings } from '../apps/web/src/strings.ts';
import { resetFake } from './support/helpers.ts';

test('regular payments show with their price rise, and the feed searches and tags transfers', async ({
  page,
  request,
}) => {
  await resetFake(request);
  const { id } = (await (await request.post('/api/dev/fake/connect')).json()) as { id: string };
  await request.post(`/api/connections/${id}/sync`);
  await expect
    .poll(async () => {
      const runs = (await (await request.get(`/api/connections/${id}/runs`)).json()) as {
        outcome: string;
      }[];
      return runs[0]?.outcome;
    })
    .toBe('ok');

  await page.goto('/outgoings');
  const energy = page.getByTestId('recurring').filter({ hasText: 'Example Energy' }).first();
  await expect(energy).toContainText(strings.cadences.monthly);
  await expect(energy).toContainText('⚠️ Up 10%: £150.00 → £165.00');
  await expect(page.getByTestId('monthly-outgoings')).not.toHaveText('£0.00');

  await page.getByRole('link', { name: strings.nav.transactions }).click();
  await expect(page.getByTestId('tx').first()).toBeVisible();
  await page.getByLabel(strings.transactions.search).fill('energy');
  await expect(page.getByTestId('tx').first()).toContainText('Example Energy');
  const rows = page.getByTestId('tx');
  await expect
    .poll(async () =>
      (await rows.allTextContents()).every((t) => t.toLowerCase().includes('energy')),
    )
    .toBe(true);

  await page.getByLabel(strings.transactions.search).fill('repayment');
  await expect(rows.first()).toContainText(strings.categories.transfer);
});
