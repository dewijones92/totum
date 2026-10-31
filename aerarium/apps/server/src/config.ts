import { z } from 'zod';

const csv = z.string().transform((s) =>
  s
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean),
);

const Env = z.object({
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  HOST: z.string().default('0.0.0.0'),
  DATA_DIR: z.string().min(1),
  STATIC_DIR: z.string().default(''),
  APP_VERSION: z.string().default('dev'),
  TRUSTED_CIDRS: csv.default(['127.0.0.0/8', '::1/128']),
  OWNER_EMAIL: z.string().default(''),
  SECRETS_KEY_FILE: z.string().default(''),
  PUBLIC_URL: z.string().default(''),
  EB_APPLICATION_ID: z.string().default(''),
  EB_PRIVATE_KEY_FILE: z.string().default(''),
  EB_REDIRECT_URL: z.string().default(''),
  EB_MAX_CONSENT_DAYS: z.coerce.number().int().min(1).max(180).default(90),
  MATRIX_HOMESERVER: z.string().default(''),
  MATRIX_ROOM: z.string().default(''),
  MATRIX_CREDS_FILE: z.string().default(''),
  LARGE_TRANSACTION_MINOR: z.coerce.number().int().min(1).default(50_000),
  FAKE_PROVIDERS: z.enum(['0', '1']).default('0'),
  MANUAL_STALE_DAYS: z.coerce.number().int().min(1).max(3650).default(90),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),
});

export type Config = z.infer<typeof Env>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = Env.safeParse(env);
  if (!parsed.success) {
    throw new Error(`Invalid configuration:\n${z.prettifyError(parsed.error)}`);
  }
  return parsed.data;
}
