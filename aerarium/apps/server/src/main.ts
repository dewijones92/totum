import { join } from 'node:path';
import { serve } from '@hono/node-server';
import { loadConfig } from './config.ts';
import { createLogger } from './log.ts';
import { SecretBox } from './secret-box.ts';
import { openDb } from './store/db.ts';
import { wire } from './wire.ts';

const config = loadConfig();
const log = createLogger(config.LOG_LEVEL);
const db = openDb(config.DATA_DIR);
const secrets = config.SECRETS_KEY_FILE
  ? SecretBox.fromKeyFile(config.SECRETS_KEY_FILE)
  : SecretBox.ephemeral();
if (secrets.ephemeral) {
  log.warn(
    'dewidebug no SECRETS_KEY_FILE: using a throwaway key, so stored tokens will not survive a restart (dev only)',
  );
}

const { app, scheduler, connections } = wire({
  db,
  secrets,
  databaseFile: join(config.DATA_DIR, 'aerarium.sqlite'),
  log,
  version: config.APP_VERSION,
  trustedCidrs: config.TRUSTED_CIDRS,
  ownerEmail: config.OWNER_EMAIL,
  fakeProviders: config.FAKE_PROVIDERS === '1',
  manualStaleDays: config.MANUAL_STALE_DAYS,
  ...(config.STATIC_DIR ? { staticDir: config.STATIC_DIR } : {}),
});

const abandoned = connections.abandonRunning(new Date().toISOString());
log.info(
  {
    version: config.APP_VERSION,
    dataDir: config.DATA_DIR,
    trusted: config.TRUSTED_CIDRS,
    ownerCheck: config.OWNER_EMAIL === '' ? 'off (no OWNER_EMAIL)' : 'on',
    fakeProviders: config.FAKE_PROVIDERS === '1',
    connections: connections.synced().map((c) => ({ id: c.id, kind: c.kind })),
    runsInterruptedByLastShutdown: abandoned,
  },
  'dewidebug starting',
);

scheduler.start();

const server = serve({ fetch: app.fetch, hostname: config.HOST, port: config.PORT }, (info) => {
  log.info({ port: info.port, host: config.HOST }, 'dewidebug listening');
});

function shutdown(signal: string): void {
  log.info({ signal }, 'dewidebug shutting down');
  scheduler.stop();
  server.close();
  db.close();
  process.exit(0);
}

process.on('SIGTERM', () => {
  shutdown('SIGTERM');
});
process.on('SIGINT', () => {
  shutdown('SIGINT');
});
