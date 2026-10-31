import { describe, expect, it } from 'vitest';
import { Healthz } from '../src/index.ts';

describe('Healthz', () => {
  it('accepts a report with no scheduler tick yet', () => {
    expect(Healthz.parse({ ok: true, version: 'dev', schedulerLastTickAt: null }).ok).toBe(true);
  });
});
