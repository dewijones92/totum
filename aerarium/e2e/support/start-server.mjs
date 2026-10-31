import { spawn } from 'node:child_process';
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const run = join(root, 'e2e', '.tmp', 'run');
rmSync(run, { recursive: true, force: true });
mkdirSync(run, { recursive: true });

const child = spawn(process.execPath, [join(root, 'apps', 'server', 'dist', 'server.mjs')], {
  stdio: 'inherit',
  env: {
    ...process.env,
    PORT: process.argv[2] ?? '8798',
    HOST: '127.0.0.1',
    DATA_DIR: join(run, 'data'),
    STATIC_DIR: join(root, 'apps', 'web', 'dist'),
    APP_VERSION: 'e2e',
    LOG_LEVEL: 'info',
  },
});
const stop = () => child.kill('SIGTERM');
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
child.on('exit', (code) => process.exit(code ?? 0));
