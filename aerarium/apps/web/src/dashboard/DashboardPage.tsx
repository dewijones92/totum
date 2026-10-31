import { AccountKind, formatMoney, money, signedBalance, type Account } from '@aerarium/shared';
import { Link } from 'wouter';
import { api } from '../api/client.ts';
import { strings } from '../strings.ts';
import { formatBalance } from '../ui/format.ts';
import { useAsync } from '../ui/use-async.ts';
import { Sparkline } from './Sparkline.tsx';
import { ReminderList } from '../reminders/ReminderLine.tsx';

const ORDER = AccountKind.options;

function groupByKind(accounts: readonly Account[]): [AccountKind, Account[]][] {
  return ORDER.map((k): [AccountKind, Account[]] => [
    k,
    accounts.filter((a) => a.kind === k),
  ]).filter(([, list]) => list.length > 0);
}

export function DashboardPage() {
  const { data, error, loading, reload } = useAsync(() => api.dashboard(), 'dashboard');
  const nudges = useAsync(() => api.nudges(), 'dashboard-nudges');
  if (error) {
    return (
      <section>
        <p className="error">{strings.common.error(error)}</p>
        <button type="button" onClick={reload}>
          {strings.common.retry}
        </button>
      </section>
    );
  }
  if (!data) return <p className="muted">{loading ? strings.dashboard.loading : null}</p>;
  const nw = data.netWorth;
  return (
    <section className="dashboard">
      <h1>{strings.dashboard.title}</h1>
      <div className="hero">
        <div className="hero-main">
          <span className="hero-label">{strings.dashboard.household}</span>
          <span className="hero-value" data-testid="net-worth">
            {formatMoney(money(nw.household))}
          </span>
        </div>
        <div className="hero-side">
          <span>{strings.dashboard.yours}</span>
          <b>{formatMoney(money(nw.yours))}</b>
          <span>{strings.dashboard.assets}</span>
          <b>{formatMoney(money(nw.assets))}</b>
          <span>{strings.dashboard.liabilities}</span>
          <b>{formatMoney(money(nw.liabilities))}</b>
        </div>
      </div>
      {data.accounts.some((a) => !a.ownerConfirmed) ? (
        <Link href="/accounts" className="note-new">
          {strings.dashboard.unconfirmed(data.accounts.filter((a) => !a.ownerConfirmed).length)}
        </Link>
      ) : null}
      {nw.leftOut.length > 0 ? (
        <p className="muted">{strings.dashboard.leftOut(nw.leftOut.length)}</p>
      ) : null}
      {nudges.data && nudges.data.reminders.length > 0 ? (
        <>
          <h2>{strings.reminders.comingUp}</h2>
          <ReminderList items={nudges.data.reminders.slice(0, 4)} />
          <Link href="/reminders">{strings.nav.reminders}</Link>
        </>
      ) : null}
      <h2>{strings.dashboard.history}</h2>
      {data.history.length > 1 ? (
        <Sparkline points={data.history} />
      ) : (
        <p className="muted">{strings.dashboard.historyTooShort}</p>
      )}
      {data.accounts.length === 0 ? (
        <div className="empty">
          <p>{strings.dashboard.empty}</p>
          <Link href="/accounts" className="button">
            {strings.dashboard.addFirst}
          </Link>
        </div>
      ) : (
        groupByKind(data.accounts).map(([kind, list]) => (
          <div key={kind} className="group">
            <h2>{strings.kinds[kind]}</h2>
            <div className="cards">
              {list.map((a) => (
                <article key={a.id} className={a.includeInNetWorth ? 'card' : 'card card-muted'}>
                  <header>
                    <span className="card-name">{a.name}</span>
                    {a.owner === 'joint' ? (
                      <span className="tag">{strings.accounts.joint}</span>
                    ) : null}
                  </header>
                  <span className={signedBalance(a) < 0 ? 'card-value negative' : 'card-value'}>
                    {formatBalance(a)}
                  </span>
                  <footer>
                    <span>{strings.sources[a.sourceKind]}</span>
                    {a.balanceAsOf ? <span>{strings.health.asOf(a.balanceAsOf)}</span> : null}
                  </footer>
                </article>
              ))}
            </div>
          </div>
        ))
      )}
    </section>
  );
}
