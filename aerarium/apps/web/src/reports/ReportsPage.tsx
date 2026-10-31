import { formatMoney, money, type Category, type WeeklyReport } from '@aerarium/shared';
import { useState } from 'react';
import { api } from '../api/client.ts';
import { strings, units } from '../strings.ts';
import { useAsync } from '../ui/use-async.ts';

const gbp = (minor: number) => formatMoney(money(minor));
const signed = (minor: number) => `${minor >= 0 ? '+' : '−'}${gbp(Math.abs(minor))}`;
const isCategory = (c: string): c is Category => c in strings.categories;

function Row({ label, value, tone }: { label: string; value: string; tone?: 'up' | 'down' }) {
  return (
    <div className="report-row">
      <span>{label}</span>
      <b className={tone === 'up' ? 'tone-up' : tone === 'down' ? 'tone-down' : ''}>{value}</b>
    </div>
  );
}

const tone = (minor: number) => (minor >= 0 ? 'up' : 'down');

function ReportCard({ r }: { r: WeeklyReport }) {
  return (
    <article className="panel report" data-testid="report">
      <h2>{strings.reports.week(r.weekStart, r.weekEnd)}</h2>
      <Row
        label={strings.reports.netWorth}
        value={r.netWorthChange === null ? strings.reports.unknown : signed(r.netWorthChange)}
        {...(r.netWorthChange === null ? {} : { tone: tone(r.netWorthChange) })}
      />
      <h3>{strings.reports.whereFrom}</h3>
      <Row label={strings.reports.saved} value={signed(r.saved)} tone={tone(r.saved)} />
      <Row label={strings.reports.markets} value={signed(r.markets)} tone={tone(r.markets)} />
      <Row label={strings.reports.interest} value={gbp(r.interest)} />
      <Row label={strings.reports.debt} value={signed(r.debtPaidDown)} />
      {r.unexplained !== null ? (
        <Row label={strings.reports.unexplained} value={signed(r.unexplained)} />
      ) : null}
      <h3>{strings.reports.spending}</h3>
      <Row label={strings.reports.spending} value={gbp(r.spending)} />
      {r.usualWeeklySpending !== null ? (
        <p className="muted">{strings.reports.usual(gbp(r.usualWeeklySpending))}</p>
      ) : null}
      {r.topCategories.length > 0 ? (
        <>
          <p className="muted">{strings.reports.topCats}</p>
          <ul>
            {r.topCategories.map((c) => (
              <li key={c.category}>
                {units.catLine(
                  isCategory(c.category) ? strings.categories[c.category] : c.category,
                  gbp(c.minor),
                  gbp(c.usualMinor),
                )}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      <h3>{strings.reports.year}</h3>
      {r.yearToDateChange !== null ? (
        <Row
          label={strings.reports.ytd}
          value={signed(r.yearToDateChange)}
          tone={tone(r.yearToDateChange)}
        />
      ) : null}
      {r.projection12Months !== null ? (
        <Row label={strings.reports.projection} value={signed(r.projection12Months)} />
      ) : null}
      {r.savingsRatePercent !== null ? (
        <Row label={strings.reports.savingsRate} value={`${String(r.savingsRatePercent)}%`} />
      ) : null}
      <p className="muted">
        {strings.reports.psaOf(
          gbp(r.interestOutsideIsaThisTaxYear),
          gbp(r.personalSavingsAllowance),
        )}
      </p>
    </article>
  );
}

export function ReportsPage() {
  const { data, error, reload } = useAsync(() => api.reports(), 'reports');
  const [busy, setBusy] = useState(false);
  const make = async () => {
    setBusy(true);
    try {
      await api.makeReport();
      console.info('dewidebug weekly report made on demand');
      reload();
    } finally {
      setBusy(false);
    }
  };
  const [latest, ...older] = data ?? [];
  return (
    <section>
      <h1>{strings.reports.title}</h1>
      <button type="button" disabled={busy} onClick={() => void make()}>
        {busy ? strings.reports.making : strings.reports.makeNow}
      </button>
      {error ? <p className="error">{strings.common.error(error)}</p> : null}
      {data?.length === 0 ? <p className="muted">{strings.reports.none}</p> : null}
      {latest ? <ReportCard r={latest} /> : null}
      {older.length > 0 ? (
        <details>
          <summary>{strings.reports.older}</summary>
          {older.map((r) => (
            <ReportCard key={r.weekEnd} r={r} />
          ))}
        </details>
      ) : null}
    </section>
  );
}
