import { pino, type Logger } from 'pino';

export type { Logger };

export function createLogger(level: string): Logger {
  return pino({
    level,
    base: { app: 'aerarium' },
    timestamp: pino.stdTimeFunctions.isoTime,
    redact: {
      paths: [
        'req.headers.cookie',
        'req.headers.authorization',
        '*.access_token',
        '*.refresh_token',
        '*.client_secret',
        '*.api_key',
      ],
      censor: '[redacted]',
    },
  });
}
