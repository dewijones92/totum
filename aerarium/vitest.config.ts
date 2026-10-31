import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: 'shared',
          root: './packages/shared',
          environment: 'node',
          include: ['test/**/*.test.ts'],
        },
      },
      {
        test: {
          name: 'server',
          root: './apps/server',
          environment: 'node',
          include: ['test/**/*.test.ts'],
          testTimeout: 20_000,
        },
      },
      './apps/web/vitest.config.ts',
      {
        test: {
          name: 'tools',
          root: './tools',
          environment: 'node',
          include: ['test/**/*.test.mjs'],
        },
      },
    ],
    coverage: {
      provider: 'v8',
      include: ['packages/shared/src/**', 'apps/server/src/**', 'apps/web/src/**'],
      exclude: [
        '**/*.d.ts',
        'apps/server/src/main.ts',
        'apps/server/src/log.ts',
        'apps/web/src/main.tsx',
      ],
      thresholds: { lines: 75, functions: 75, branches: 70, statements: 75 },
    },
  },
});
