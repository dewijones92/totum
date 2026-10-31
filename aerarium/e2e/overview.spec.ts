import { expect, test, type APIRequestContext } from '@playwright/test';
import { formatMoney, money } from '../packages/shared/src/index.ts';
import { strings } from '../apps/web/src/strings.ts';

async function household(request: APIRequestContext): Promise<number> {
  const body = (await (await request.get('/api/dashboard')).json()) as {
    netWorth: { household: number };
  };
  return body.netWorth.household;
}

test('the server answers its health check', async ({ request }) => {
  const health = await request.get('/healthz');
  expect(await health.json()).toMatchObject({ ok: true, version: 'e2e' });
});

test('a typed-in balance moves net worth by exactly its amount, and so does an update', async ({
  page,
  request,
}) => {
  const before = await household(request);
  await page.goto('/accounts');
  await page.getByLabel(strings.accounts.name).fill('E2E savings');
  await page.getByLabel(strings.accounts.kind).selectOption('savings');
  await page.getByLabel(strings.accounts.balance).fill('1234.56');
  await page.getByRole('button', { name: strings.accounts.add }).click();
  const row = page.getByTestId('account-E2E savings');
  await expect(row).toContainText('£1,234.56');

  await page.getByRole('link', { name: strings.nav.dashboard }).click();
  await expect(page.getByTestId('net-worth')).toHaveText(formatMoney(money(before + 123_456)));

  await page.getByRole('link', { name: strings.nav.accounts }).click();
  await row.getByPlaceholder('1234.56').fill('2000');
  await row.getByRole('button', { name: strings.accounts.update }).click();
  await expect(row).toContainText('£2,000.00');
  await page.getByRole('link', { name: strings.nav.dashboard }).click();
  await expect(page.getByTestId('net-worth')).toHaveText(formatMoney(money(before + 200_000)));
});
