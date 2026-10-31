import { serve } from '@hono/node-server';
import { createApp } from './app.ts';
import { loadConfig } from './config.ts';
import { createLogger } from './log.ts';

const config = loadConfig();
const log = createLogger(config.LOG_LEVEL);

log.info(
  {
    version: config.APP_VERSION,
    dataDir: config.DATA_DIR,
    trusted: config.TRUSTED_CIDRS,
    ownerCheck: config.OWNER_EMAIL === '' ? 'off (no OWNER_EMAIL)' : 'on',
  },
  'dewidebug starting',
);

const app = createApp({
  log,
  version: config.APP_VERSION,
  trustedCidrs: config.TRUSTED_CIDRS,
  ownerEmail: config.OWNER_EMAIL,
  schedulerLastTickAt: () => null,
  ...(config.STATIC_DIR ? { staticDir: config.STATIC_DIR } : {}),
});

const server = serve({ fetch: app.fetch, hostname: config.HOST, port: config.PORT }, (info) => {
  log.info({ port: info.port, host: config.HOST }, 'dewidebug listening');
});

function shutdown(signal: string): void {
  log.info({ signal }, 'dewidebug shutting down');
  server.close();
  process.exit(0);
}

process.on('SIGTERM', () => {
  shutdown('SIGTERM');
});
process.on('SIGINT', () => {
  shutdown('SIGINT');
});
