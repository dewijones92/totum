import { Link, Route, Switch, useLocation } from 'wouter';
import { AccountsPage } from './accounts/AccountsPage.tsx';
import { ConnectionsPage } from './connections/ConnectionsPage.tsx';
import { OutgoingsPage } from './outgoings/OutgoingsPage.tsx';
import { ReportsPage } from './reports/ReportsPage.tsx';
import { TransactionsPage } from './transactions/TransactionsPage.tsx';
import { DashboardPage } from './dashboard/DashboardPage.tsx';
import { HealthBar } from './health/HealthBar.tsx';
import { HealthPage } from './health/HealthPage.tsx';
import { useHealth } from './health/use-health.ts';
import { names, strings, tips } from './strings.ts';

const NAV: { href: string; label: string }[] = [
  { href: '/', label: strings.nav.dashboard },
  { href: '/accounts', label: strings.nav.accounts },
  { href: '/transactions', label: strings.nav.transactions },
  { href: '/outgoings', label: strings.nav.outgoings },
  { href: '/reports', label: strings.nav.reports },
  { href: '/connections', label: strings.nav.connections },
  { href: '/health', label: strings.nav.health },
];

export function App() {
  const health = useHealth();
  const [location] = useLocation();
  return (
    <div className="app">
      <header className="top">
        <Link href="/" className="brand">
          <img src="/favicon.svg" alt="" width={28} height={28} />
          <span>{names.app}</span>
        </Link>
        <nav aria-label={tips.nav}>
          {NAV.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              className={location === n.href ? 'nav-link active' : 'nav-link'}
            >
              {n.label}
            </Link>
          ))}
        </nav>
      </header>
      <HealthBar report={health.data} />
      <main className="shell">
        <Switch>
          <Route path="/">
            <DashboardPage />
          </Route>
          <Route path="/accounts">
            <AccountsPage onChange={health.reload} />
          </Route>
          <Route path="/transactions">
            <TransactionsPage />
          </Route>
          <Route path="/outgoings">
            <OutgoingsPage />
          </Route>
          <Route path="/reports">
            <ReportsPage />
          </Route>
          <Route path="/connections">
            <ConnectionsPage onChange={health.reload} />
          </Route>
          <Route path="/health">
            <HealthPage health={health} />
          </Route>
        </Switch>
      </main>
    </div>
  );
}
