import { formatMoney, money, type RecurringDto } from '@aerarium/shared';
import { api } from '../api/client.ts';
import { strings } from '../strings.ts';
import { useAsync } from '../ui/use-async.ts';

const gbp = (minor: number) => formatMoney(money(minor));

function RecurringCard({ r }: { r: RecurringDto }) {
  return (
    <article className={r.lapsed ? 'card card-muted' : 'card'} data-testid="recurring">
      <header>
        <span className="card-name">{r.payee}</span>
        {r.owner === 'joint' ? <span className="tag">{strings.accounts.joint}</span> : null}
      </header>
      <span className="card-value">{gbp(r.lastMinor)}</span>
      <footer>
        <span>{strings.cadences[r.cadence]}</span>
        <span>{strings.categories[r.category]}</span>
        <span>{strings.outgoings.perMonth(gbp(r.monthlyMinor))}</span>
        <span>{r.lapsed ? strings.outgoings.lapsed : strings.outgoings.next(r.nextDue)}</span>
        <span>{strings.outgoings.last(r.lastOn)}</span>
        <span>{strings.outgoings.times(r.count)}</span>
        <span className="muted">{r.accountName}</span>
      </footer>
      {r.priceChange ? (
        <p className={r.priceChange.percent > 0 ? 'price-up' : 'price-down'}>
          {r.priceChange.percent > 0
            ? strings.outgoings.rise(
                r.priceChange.percent,
                gbp(r.priceChange.fromMinor),
                gbp(r.priceChange.toMinor),
              )
            : strings.outgoings.fall(
                r.priceChange.percent,
                gbp(r.priceChange.fromMinor),
                gbp(r.priceChange.toMinor),
              )}
        </p>
      ) : null}
    </article>
  );
}

export function OutgoingsPage() {
  const { data, error, loading } = useAsync(() => api.outgoings(), 'outgoings');
  if (error) return <p className="error">{strings.common.error(error)}</p>;
  if (!data) return <p className="muted">{loading ? strings.outgoings.loading : null}</p>;
  const live = data.recurring.filter((r) => !r.lapsed);
  const stopped = data.recurring.filter((r) => r.lapsed);
  return (
    <section>
      <h1>{strings.outgoings.title}</h1>
      <div className="hero">
        <div className="hero-main">
          <span className="hero-label">{strings.outgoings.monthly}</span>
          <span className="hero-value" data-testid="monthly-outgoings">
            {gbp(data.monthlyMinor)}
          </span>
          {data.jointMonthlyMinor > 0 ? (
            <span className="muted">
              {strings.outgoings.jointPart(gbp(data.jointMonthlyMinor))}
            </span>
          ) : null}
        </div>
      </div>
      <h2>{strings.outgoings.dueSoon}</h2>
      {data.dueSoon.length === 0 ? <p className="muted">{strings.outgoings.nothingDue}</p> : null}
      <div className="cards">
        {data.dueSoon.map((r) => (
          <RecurringCard key={`soon-${r.key}`} r={r} />
        ))}
      </div>
      <h2>{strings.outgoings.all}</h2>
      {data.recurring.length === 0 ? <p className="muted">{strings.outgoings.none}</p> : null}
      <div className="cards">
        {[...live, ...stopped].map((r) => (
          <RecurringCard key={r.key} r={r} />
        ))}
      </div>
    </section>
  );
}
