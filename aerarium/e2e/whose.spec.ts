import { expect, test } from '@playwright/test';
import { strings } from '../apps/web/src/strings.ts';
import { resetFake } from './support/helpers.ts';

test('a newly synced account asks whose it is, and Joint sticks as a label', async ({
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

  await page.goto('/');
  await page.getByRole('link', { name: /new account/ }).click();
  await expect(page.locator('.account-row-new').first()).toBeVisible();
  const prompts = page.getByText(strings.accounts.whose);
  const jointTags = page.locator('.account-row .tag', { hasText: strings.accounts.joint });
  const before = { prompts: await prompts.count(), joint: await jointTags.count() };
  expect(before.prompts).toBeGreaterThan(0);

  const first = page.locator('.account-row-new').first();
  await first.getByRole('button', { name: strings.accounts.markJoint }).click();
  await expect(prompts).toHaveCount(before.prompts - 1);
  await expect(jointTags).toHaveCount(before.joint + 1);
});
