import { expect, test } from '@playwright/test';

test('the app answers its health check and shows its name', async ({ page, request }) => {
  const health = await request.get('/healthz');
  expect(health.ok()).toBe(true);
  expect(await health.json()).toMatchObject({ ok: true, version: 'e2e' });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Aerarium' })).toBeVisible();
});
