import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { createApp } from '../src/app.ts';

const log = pino({ level: 'silent' });

function appFor(opts: { ip: string; ownerEmail: string }) {
  return createApp({
    log,
    version: 'test',
    trustedCidrs: ['172.21.0.0/16'],
    ownerEmail: opts.ownerEmail,
    schedulerLastTickAt: () => null,
    remoteAddress: () => opts.ip,
  });
}

describe('guards', () => {
  it('serves /healthz to anyone, with the version', async () => {
    const res = await appFor({ ip: '8.8.8.8', ownerEmail: 'me@example.com' }).request('/healthz');
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ ok: true, version: 'test' });
  });

  it('refuses a request from outside the trusted network', async () => {
    const res = await appFor({ ip: '8.8.8.8', ownerEmail: '' }).request('/api/anything');
    expect(res.status).toBe(403);
  });

  it('refuses a signed-in person who is not the owner', async () => {
    const res = await appFor({ ip: '172.21.0.5', ownerEmail: 'me@example.com' }).request(
      '/api/anything',
      { headers: { 'x-forwarded-email': 'someone-else@example.com' } },
    );
    expect(res.status).toBe(403);
    expect(await res.text()).toContain('private');
  });

  it('refuses a request with no email when an owner is configured', async () => {
    const res = await appFor({ ip: '172.21.0.5', ownerEmail: 'me@example.com' }).request(
      '/api/anything',
    );
    expect(res.status).toBe(403);
  });

  it('lets the owner through, ignoring case', async () => {
    const res = await appFor({ ip: '172.21.0.5', ownerEmail: 'me@example.com' }).request(
      '/api/anything',
      { headers: { 'x-forwarded-email': 'Me@Example.com' } },
    );
    expect(res.status).toBe(404);
  });
});
