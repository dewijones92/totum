import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { HealthReport } from '@aerarium/shared';
import { HealthBar, problemsOf } from '../src/health/HealthBar.tsx';
import { reasonText } from '../src/strings.ts';

const report = (over: Partial<HealthReport>): HealthReport => ({
  overall: 'ok',
  sources: [],
  manual: [],
  app: {
    state: 'ok',
    reasons: [{ code: 'healthy' }],
    version: 't',
    schedulerLastTickAt: null,
    databaseBytes: 0,
  },
  ...over,
});

vi.mock('wouter', () => ({
  Link: ({ children }: { children: unknown }) => children,
}));

describe('HealthBar', () => {
  it('shows nothing when all is well', () => {
    const { container } = render(<HealthBar report={report({})} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shouts about a broken source, naming it and the reason', () => {
    render(
      <HealthBar
        report={report({
          overall: 'fail',
          sources: [
            {
              connectionId: 'c1',
              label: 'Example Bank',
              kind: 'fake',
              state: 'fail',
              reasons: [{ code: 'consent-expired', at: '2026-10-30T00:00:00Z' }],
              lastOkAt: null,
              consentExpiresAt: '2026-10-30T00:00:00Z',
              lastRun: null,
            },
          ],
        })}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('🔴 1 source needs you');
    expect(screen.getByRole('alert')).toHaveTextContent('Example Bank');
    expect(screen.getByRole('alert')).toHaveTextContent('reconnect');
  });

  it('lists failures before warnings', () => {
    const problems = problemsOf(
      report({
        overall: 'fail',
        manual: [
          {
            accountId: 'm',
            name: 'Old savings',
            state: 'warn',
            reasons: [{ code: 'manual-stale', days: 120 }],
            asOf: '2026-07-01',
          },
        ],
        app: {
          state: 'fail',
          reasons: [{ code: 'scheduler-silent', minutes: 9 }],
          version: 't',
          schedulerLastTickAt: null,
          databaseBytes: 0,
        },
      }),
    );
    expect(problems.map((p) => p.state)).toEqual(['fail', 'warn']);
  });
});

describe('reasonText', () => {
  it('says something for every reason', () => {
    expect(reasonText({ code: 'scheduler-silent', minutes: -1 })).toContain('not started');
    expect(reasonText({ code: 'consent-expiring', days: 1 })).toContain('1 day');
    expect(reasonText({ code: 'stale', hours: 30 })).toContain('30 hours');
  });
});
