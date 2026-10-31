import type { HealthReport, HealthState } from '@aerarium/shared';
import { Link } from 'wouter';
import { reasonText, strings } from '../strings.ts';

interface Problem {
  key: string;
  label: string;
  reason: string;
  state: HealthState;
}

export function problemsOf(report: HealthReport): Problem[] {
  const out: Problem[] = [];
  for (const s of report.sources) {
    if (s.state !== 'ok' && s.reasons[0]) {
      out.push({
        key: s.connectionId,
        label: s.label,
        reason: reasonText(s.reasons[0]),
        state: s.state,
      });
    }
  }
  for (const m of report.manual) {
    if (m.state !== 'ok' && m.reasons[0]) {
      out.push({
        key: m.accountId,
        label: m.name,
        reason: reasonText(m.reasons[0]),
        state: m.state,
      });
    }
  }
  if (report.app.state !== 'ok' && report.app.reasons[0]) {
    out.push({
      key: 'app',
      label: strings.health.app,
      reason: reasonText(report.app.reasons[0]),
      state: report.app.state,
    });
  }
  return out.sort((a, b) => (a.state === b.state ? 0 : a.state === 'fail' ? -1 : 1));
}

export function HealthBar({ report }: { report: HealthReport | undefined }) {
  if (!report || report.overall === 'ok') return null;
  const problems = problemsOf(report);
  const failing = problems.filter((p) => p.state === 'fail');
  const loud = failing.length > 0;
  const shown = loud ? failing : problems;
  return (
    <div
      className={`health-bar health-bar-${loud ? 'fail' : 'warn'}`}
      role={loud ? 'alert' : 'status'}
    >
      <strong>
        {loud ? strings.health.barFail(shown.length) : strings.health.barWarn(shown.length)}
      </strong>
      <ul>
        {shown.map((p) => (
          <li key={p.key}>
            <b>{p.label}</b> {p.reason}
          </li>
        ))}
      </ul>
      <Link href="/health" className="health-bar-link">
        {strings.health.open}
      </Link>
    </div>
  );
}
