import { expect, test } from '@playwright/test';
import { strings } from '../apps/web/src/strings.ts';
import { resetFake } from './support/helpers.ts';

test('a broken source turns the bar red on every page, and recovers', async ({ page, request }) => {
  await resetFake(request);
  const { id } = (await (await request.post('/api/dev/fake/connect')).json()) as { id: string };
  await request.post('/api/dev/fake/fail', { data: { error: 'HTTP 503 from the bank' } });
  await request.post(`/api/connections/${id}/sync`);

  await page.goto('/health');
  const tile = page.getByTestId('source-Example Bank (fake)');
  await expect(tile).toContainText('HTTP 503 from the bank');
  await page.goto('/accounts');
  await expect(page.getByRole('alert')).toContainText(strings.health.barFail(1));
  await expect(page.getByRole('alert')).toContainText('HTTP 503 from the bank');
  await page.goto('/health');

  await resetFake(request);
  await tile.getByRole('button', { name: strings.health.syncNow }).click();
  await expect(tile).toContainText(strings.health.state.ok, { timeout: 15_000 });

  await page.goto('/');
  await expect(page.getByText('Example Card')).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});
