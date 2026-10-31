import {
  AccountKind,
  LIABILITY_KINDS,
  Owner,
  parseMajorToMinor,
  type Account,
} from '@aerarium/shared';
import { useState, type SyntheticEvent } from 'react';
import { api } from '../api/client.ts';
import { strings, tips } from '../strings.ts';
import { formatBalance } from '../ui/format.ts';
import { useAsync } from '../ui/use-async.ts';

const today = () => new Date().toISOString().slice(0, 10);

function AddManual({ onAdded }: { onAdded: () => void }) {
  const [name, setName] = useState('');
  const [kind, setKind] = useState<AccountKind>('savings');
  const [owner, setOwner] = useState<Owner>('mine');
  const [amount, setAmount] = useState('');
  const [asOf, setAsOf] = useState(today);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault();
    const minorValue = parseMajorToMinor(amount);
    if (minorValue === null) {
      setError(strings.accounts.badAmount);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.addManual({ name, kind, owner, currency: 'GBP', balanceMinor: minorValue, asOf });
      console.info('dewidebug manual account added', { kind, owner });
      setName('');
      setAmount('');
      onAdded();
    } catch (err) {
      setError(strings.common.error(err instanceof Error ? err.message : String(err)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="panel add-form" onSubmit={(e) => void submit(e)}>
      <h2>{strings.accounts.addTitle}</h2>
      <p className="muted">{strings.accounts.addHint}</p>
      <label>
        <span>{strings.accounts.name}</span>
        <input
          required
          value={name}
          placeholder={tips.namePlaceholder}
          onChange={(e) => {
            setName(e.target.value);
          }}
        />
      </label>
      <label>
        <span>{strings.accounts.kind}</span>
        <select
          value={kind}
          onChange={(e) => {
            setKind(AccountKind.parse(e.target.value));
          }}
        >
          {AccountKind.options.map((k) => (
            <option key={k} value={k}>
              {strings.kinds[k]}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>{strings.accounts.owner}</span>
        <select
          value={owner}
          onChange={(e) => {
            setOwner(Owner.parse(e.target.value));
          }}
        >
          {Owner.options.map((o) => (
            <option key={o} value={o}>
              {strings.owners[o]}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>{LIABILITY_KINDS.has(kind) ? strings.accounts.owed : strings.accounts.balance}</span>
        <input
          required
          inputMode="decimal"
          value={amount}
          placeholder={tips.amountPlaceholder}
          onChange={(e) => {
            setAmount(e.target.value);
          }}
        />
      </label>
      <label>
        <span>{strings.accounts.asOf}</span>
        <input
          required
          type="date"
          value={asOf}
          onChange={(e) => {
            setAsOf(e.target.value);
          }}
        />
      </label>
      <button type="submit" disabled={busy}>
        {busy ? strings.accounts.adding : strings.accounts.add}
      </button>
      {error ? <p className="error">{error}</p> : null}
    </form>
  );
}

function AccountRow({ account, onChange }: { account: Account; onChange: () => void }) {
  const manual = account.sourceKind === 'manual';
  const [amount, setAmount] = useState('');
  const [asOf, setAsOf] = useState(today);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const act = async (work: () => Promise<unknown>, what: string) => {
    setBusy(true);
    setError(null);
    try {
      await work();
      console.info('dewidebug account action done', { what, id: account.id });
      onChange();
    } catch (err) {
      setError(strings.common.error(err instanceof Error ? err.message : String(err)));
    } finally {
      setBusy(false);
    }
  };

  const saveBalance = () => {
    const minorValue = parseMajorToMinor(amount);
    if (minorValue === null) {
      setError(strings.accounts.badAmount);
      return;
    }
    void act(
      () => api.recordBalance(account.id, { balanceMinor: minorValue, asOf }),
      'balance',
    ).then(() => {
      setAmount('');
    });
  };

  return (
    <li
      className={account.ownerConfirmed ? 'account-row' : 'account-row account-row-new'}
      data-testid={`account-${account.name}`}
    >
      {account.ownerConfirmed ? null : (
        <div className="whose">
          <b>{strings.accounts.whose}</b>
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              void act(() => api.patchAccount(account.id, { owner: 'mine' }), 'confirm-mine')
            }
          >
            {strings.accounts.markMine}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              void act(() => api.patchAccount(account.id, { owner: 'joint' }), 'confirm-joint')
            }
          >
            {strings.accounts.markJoint}
          </button>
        </div>
      )}
      <div className="account-main">
        <b>{account.name}</b>
        {account.owner === 'joint' ? <span className="tag">{strings.accounts.joint}</span> : null}
        <span className="muted">
          {strings.kinds[account.kind]} ·{' '}
          {manual ? strings.accounts.typedIn : strings.accounts.synced}
        </span>
        <span className="account-balance">{formatBalance(account)}</span>
        {account.balanceAsOf ? (
          <span className="muted">{strings.health.asOf(account.balanceAsOf)}</span>
        ) : null}
      </div>
      <div className="account-controls">
        <select
          value={account.owner}
          disabled={busy}
          onChange={(e) =>
            void act(
              () => api.patchAccount(account.id, { owner: Owner.parse(e.target.value) }),
              'owner',
            )
          }
        >
          {Owner.options.map((o) => (
            <option key={o} value={o}>
              {strings.owners[o]}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() =>
            void act(
              () => api.patchAccount(account.id, { includeInNetWorth: !account.includeInNetWorth }),
              'include',
            )
          }
        >
          {account.includeInNetWorth ? strings.accounts.counted : strings.accounts.notCounted}
        </button>
        {manual ? (
          <>
            <input
              inputMode="decimal"
              value={amount}
              placeholder={tips.amountPlaceholder}
              onChange={(e) => {
                setAmount(e.target.value);
              }}
            />
            <input
              type="date"
              value={asOf}
              onChange={(e) => {
                setAsOf(e.target.value);
              }}
            />
            <button type="button" disabled={busy || amount === ''} onClick={saveBalance}>
              {busy ? strings.accounts.updating : strings.accounts.update}
            </button>
            <button
              type="button"
              className="danger"
              disabled={busy}
              onClick={() => {
                if (window.confirm(strings.accounts.confirmRemove))
                  void act(() => api.deleteAccount(account.id), 'delete');
              }}
            >
              {strings.accounts.remove}
            </button>
          </>
        ) : null}
      </div>
      {error ? <p className="error">{error}</p> : null}
    </li>
  );
}

export function AccountsPage({ onChange }: { onChange: () => void }) {
  const { data, error, reload } = useAsync(() => api.accounts(), 'accounts');
  const changed = () => {
    reload();
    onChange();
  };
  return (
    <section>
      <h1>{strings.accounts.title}</h1>
      <AddManual onAdded={changed} />
      {error ? <p className="error">{strings.common.error(error)}</p> : null}
      <ul className="account-list">
        {[...(data ?? [])]
          .sort((a, b) => Number(a.ownerConfirmed) - Number(b.ownerConfirmed))
          .map((a) => (
            <AccountRow key={a.id} account={a} onChange={changed} />
          ))}
      </ul>
    </section>
  );
}
