import { getConnInfo } from '@hono/node-server/conninfo';
import { serveStatic } from '@hono/node-server/serve-static';
import type { Healthz } from '@aerarium/shared';
import { Hono, type Context } from 'hono';
import { isTrusted } from './http/ip.ts';
import { EMAIL_HEADER, judgeOwner } from './http/owner.ts';
import type { Logger } from './log.ts';

export interface AppDeps {
  log: Logger;
  version: string;
  trustedCidrs: readonly string[];
  ownerEmail: string;
  staticDir?: string;
  schedulerLastTickAt: () => string | null;
  remoteAddress?: (c: Context) => string | undefined;
}

function remoteFromConnInfo(c: Context): string | undefined {
  try {
    return getConnInfo(c).remote.address;
  } catch {
    return undefined;
  }
}

export function createApp(deps: AppDeps): Hono {
  const { log } = deps;
  const remoteAddress = deps.remoteAddress ?? remoteFromConnInfo;
  const app = new Hono();

  app.use('*', async (c, next) => {
    const started = performance.now();
    await next();
    const ms = Math.round(performance.now() - started);
    const entry = { method: c.req.method, path: c.req.path, status: c.res.status, ms };
    if (c.res.status >= 500) log.error(entry, 'dewidebug request failed');
    else if (c.req.path.startsWith('/api/')) log.info(entry, 'dewidebug request');
  });

  app.get('/healthz', (c) => {
    const body: Healthz = {
      ok: true,
      version: deps.version,
      schedulerLastTickAt: deps.schedulerLastTickAt(),
    };
    return c.json(body);
  });

  app.use('*', async (c: Context, next) => {
    const ip = remoteAddress(c);
    if (!isTrusted(ip, deps.trustedCidrs)) {
      log.warn({ ip, path: c.req.path }, 'dewidebug refused: request not from a trusted network');
      return c.text('Forbidden', 403);
    }
    const verdict = judgeOwner(c.req.header(EMAIL_HEADER), deps.ownerEmail);
    if (verdict.kind === 'stranger') {
      log.warn(
        { email: verdict.email, path: c.req.path },
        'dewidebug refused: signed in, but not the owner',
      );
      return c.text('🔒 Aerarium is private to its owner.', 403);
    }
    await next();
  });

  if (deps.staticDir) {
    const root = deps.staticDir;
    app.use('/*', serveStatic({ root }));
    app.get('*', serveStatic({ root, path: 'index.html' }));
  }

  return app;
}
