import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.ts';

describe('loadConfig', () => {
  it('applies defaults and splits trusted networks', () => {
    const c = loadConfig({ DATA_DIR: '/tmp/x', TRUSTED_CIDRS: '172.21.0.0/16, 127.0.0.0/8' });
    expect(c.PORT).toBe(8080);
    expect(c.TRUSTED_CIDRS).toEqual(['172.21.0.0/16', '127.0.0.0/8']);
    expect(c.OWNER_EMAIL).toBe('');
  });

  it('refuses a missing data dir with a readable message', () => {
    expect(() => loadConfig({})).toThrow(/Invalid configuration[\s\S]*DATA_DIR/);
  });
});
