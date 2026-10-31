import { describe, expect, it } from 'vitest';
import { pino } from 'pino';
import { TrustedPeers } from '../src/http/trusted-peers.ts';
import { openMemoryDb } from '../src/store/db.ts';
import { SecretBox } from '../src/secret-box.ts';
import { wire } from '../src/wire.ts';

const log = pino({ level: 'silent' });

describe('TrustedPeers', () => {
  it('trusts the resolved proxy, not its neighbours, follows it when it moves, and fails closed', async () => {
    let now = 0;
    let addresses = ['172.21.0.9'];
    let broken = false;
    const peers = new TrustedPeers({
      cidrs: ['127.0.0.0/8'],
      hosts: ['nginx'],
      log,
      clock: () => now,
      resolve: () => (broken ? Promise.reject(new Error('ENOTFOUND')) : Promise.resolve(addresses)),
    });
    expect(await peers.allows('172.21.0.9')).toBe(true);
    expect(await peers.allows('::ffff:172.21.0.9')).toBe(true);
    expect(await peers.allows('172.21.0.5')).toBe(false);
    expect(await peers.allows('127.0.0.1')).toBe(true);
    addresses = ['172.21.0.10'];
    now += 5_000;
    expect(await peers.allows('172.21.0.10')).toBe(true);
    expect(await peers.allows('172.21.0.9')).toBe(false);
    broken = true;
    now += 60_000;
    expect(await peers.allows('172.21.0.10')).toBe(false);
  });
});

describe('the server behind the proxy', () => {
  it('refuses another container on the shared network even with the owner email', async () => {
    let ip = '172.21.0.9';
    const w = wire({
      db: openMemoryDb(),
      secrets: SecretBox.ephemeral(),
      databaseFile: null,
      log,
      version: 'test',
      trustedCidrs: ['127.0.0.0/8'],
      trustedHosts: ['nginx_dewi'],
      resolveHost: () => Promise.resolve(['172.21.0.9']),
      ownerEmail: 'me@example.com',
      fakeProviders: false,
      manualStaleDays: 90,
      remoteAddress: () => ip,
    });
    const asOwner = { headers: { 'x-forwarded-email': 'me@example.com' } };
    expect((await w.app.request('/api/accounts', asOwner)).status).toBe(200);
    ip = '172.21.0.5';
    expect((await w.app.request('/api/accounts', asOwner)).status).toBe(403);
  });
});
