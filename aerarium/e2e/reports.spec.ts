import { expect, test } from '@playwright/test';
import { strings } from '../apps/web/src/strings.ts';

test('last week’s report can be made on demand and shows where the money went', async ({
  page,
}) => {
  await page.goto('/reports');
  await page.getByRole('button', { name: strings.reports.makeNow }).click();
  const report = page.getByTestId('report').first();
  await expect(report).toContainText(strings.reports.whereFrom);
  await expect(report).toContainText(strings.reports.saved);
  await expect(report).toContainText('Personal Savings Allowance');
  await expect(report.getByRole('heading', { level: 2 })).toHaveText(
    /🗓️ \d{4}-\d{2}-\d{2} to \d{4}-\d{2}-\d{2}/,
  );
});
