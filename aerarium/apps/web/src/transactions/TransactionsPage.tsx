import { Category, formatMoney, money } from '@aerarium/shared';
import { useState } from 'react';
import { api } from '../api/client.ts';
import { strings, tips } from '../strings.ts';
import { useAsync } from '../ui/use-async.ts';

const LIMIT = 300;

export function TransactionsPage() {
  const [q, setQ] = useState('');
  const [account, setAccount] = useState('');
  const [category, setCategory] = useState<Category | ''>('');
  const accounts = useAsync(() => api.accounts(), 'tx-accounts');
  const key = `tx|${q}|${account}|${category}`;
  const { data, error, loading } = useAsync(
    () =>
      api.transactions({
        limit: LIMIT,
        ...(q ? { q } : {}),
        ...(account ? { account } : {}),
        ...(category ? { category } : {}),
      }),
    key,
  );
  return (
    <section>
      <h1>{strings.transactions.title}</h1>
      <div className="filters">
        <label>
          <span>{strings.transactions.search}</span>
          <input
            value={q}
            placeholder={tips.txSearch}
            onChange={(e) => {
              setQ(e.target.value);
            }}
          />
        </label>
        <label>
          <span>{strings.transactions.allAccounts}</span>
          <select
            value={account}
            onChange={(e) => {
              setAccount(e.target.value);
            }}
          >
            <option value="">{strings.transactions.allAccounts}</option>
            {(accounts.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>
                {a.owner === 'joint' ? `${a.name} · ${strings.accounts.joint}` : a.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>{strings.transactions.allCategories}</span>
          <select
            value={category}
            onChange={(e) => {
              const parsed = Category.safeParse(e.target.value);
              setCategory(parsed.success ? parsed.data : '');
            }}
          >
            <option value="">{strings.transactions.allCategories}</option>
            {Category.options.map((c) => (
              <option key={c} value={c}>
                {strings.categories[c]}
              </option>
            ))}
          </select>
        </label>
      </div>
      {error ? <p className="error">{strings.common.error(error)}</p> : null}
      {loading && !data ? <p className="muted">{strings.transactions.loading}</p> : null}
      {data?.length === 0 ? <p className="muted">{strings.transactions.none}</p> : null}
      <ul className="tx-list">
        {(data ?? []).map((t) => (
          <li key={t.id} className={t.transfer ? 'tx tx-transfer' : 'tx'} data-testid="tx">
            <span className="tx-date">{t.bookedOn}</span>
            <span className="tx-what">
              <b>{t.counterparty ?? t.description}</b>
              {t.counterparty ? <span className="muted">{t.description}</span> : null}
            </span>
            <span className="tx-meta">
              <span className="tag">{strings.categories[t.category]}</span>
              <span className="muted">{t.accountName}</span>
              {t.owner === 'joint' ? <span className="tag">{strings.accounts.joint}</span> : null}
            </span>
            <span className={t.minor < 0 ? 'tx-amount out' : 'tx-amount in'}>
              {formatMoney(money(t.minor, t.currency))}
            </span>
          </li>
        ))}
      </ul>
      {data?.length === LIMIT ? (
        <p className="muted">{strings.transactions.showing(LIMIT)}</p>
      ) : null}
    </section>
  );
}
