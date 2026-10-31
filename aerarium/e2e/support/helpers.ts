import type { APIRequestContext } from '@playwright/test';

export async function resetFake(request: APIRequestContext): Promise<void> {
  await request.post('/api/dev/fake/fail', { data: { error: null } });
}
