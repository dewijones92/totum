import { describe, expect, it } from 'vitest';
import { ipInCidr, isTrusted } from '../src/http/ip.ts';

describe('ipInCidr', () => {
  it.each([
    ['172.21.3.4', '172.21.0.0/16', true],
    ['172.22.0.1', '172.21.0.0/16', false],
    ['::ffff:127.0.0.1', '127.0.0.0/8', true],
    ['10.0.0.1', '0.0.0.0/0', true],
    ['10.0.0.1', '10.0.0.1', true],
    ['::1', '::1/128', true],
    ['::2', '::1/128', false],
    ['::1', '::1/64', false],
    ['10.0.0.1', '::1/128', false],
    ['300.0.0.1', '10.0.0.0/8', false],
    ['10.0.0.1', '10.0.0.0/33', false],
  ])('%s in %s is %s', (ip, cidr, expected) => {
    expect(ipInCidr(ip, cidr)).toBe(expected);
  });

  it('treats a missing address as untrusted', () => {
    expect(isTrusted(undefined, ['0.0.0.0/0'])).toBe(false);
  });
});
