import { join } from 'node:path';
import { serve } from '@hono/node-server';
import { loadConfig } from './config.ts';
import { createLogger } from './log.ts';
import { SecretBox } from './secret-box.ts';
import { openDb } from './store/db.ts';
import { wire } from './wire.ts';
import { MatrixNotifier } from './notify/notifier.ts';
import { Trading212Connector } from './connectors/trading212.ts';
import { EnableBankingClient } from './connectors/enable-banking.ts';
import { readFileSync } from 'node:fs';

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

const enableBanking =
  config.EB_APPLICATION_ID &&
  config.EB_PRIVATE_KEY_FILE &&
  (config.EB_REDIRECT_URL || config.PUBLIC_URL)
    ? {
        client: new EnableBankingClient(
          {
            applicationId: config.EB_APPLICATION_ID,
            privateKeyPem: readFileSync(config.EB_PRIVATE_KEY_FILE, 'utf8'),
            redirectUrl:
              config.EB_REDIRECT_URL || `${config.PUBLIC_URL.replace(/\/$/, '')}/api/eb/callback`,
          },
          log,
        ),
        maxConsentDays: config.EB_MAX_CONSENT_DAYS,
      }
    : undefined;

const notifier =
  config.MATRIX_HOMESERVER && config.MATRIX_ROOM && config.MATRIX_CREDS_FILE
    ? new MatrixNotifier({
        homeserver: config.MATRIX_HOMESERVER,
        room: config.MATRIX_ROOM,
        credsFile: config.MATRIX_CREDS_FILE,
        log,
      })
    : undefined;

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
  connectors: [new Trading212Connector(log)],
  ...(enableBanking ? { enableBanking } : {}),
  ...(config.PUBLIC_URL ? { publicUrl: config.PUBLIC_URL } : {}),
  ...(notifier ? { notifier } : {}),
  largeTransactionMinor: config.LARGE_TRANSACTION_MINOR,
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
    notifications: notifier ? 'matrix' : 'log only (MATRIX_* not set)',
    openBanking: enableBanking
      ? 'on'
      : 'off (EB_APPLICATION_ID, EB_PRIVATE_KEY_FILE or PUBLIC_URL missing)',
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
