import { useMemo, useState, type SyntheticEvent } from 'react';
import { useSearch } from 'wouter';
import { api } from '../api/client.ts';
import { strings, tips } from '../strings.ts';
import { useAsync } from '../ui/use-async.ts';

type T212Kind = 'isa-stocks' | 'investment' | 'pension';
const T212_KINDS: readonly T212Kind[] = ['isa-stocks', 'investment', 'pension'];

function AddTrading212({ onAdded }: { onAdded: () => void }) {
  const [keyId, setKeyId] = useState('');
  const [secret, setSecret] = useState('');
  const [accountName, setAccountName] = useState('Trading 212');
  const [accountKind, setAccountKind] = useState<T212Kind>('isa-stocks');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault();
    setBusy(true);
    setNote(null);
    try {
      await api.connectTrading212({ keyId, secret, accountName, accountKind });
      console.info('dewidebug trading212 connected', { accountKind });
      setKeyId('');
      setSecret('');
      setNote(strings.connections.connected);
      onAdded();
    } catch (err) {
      setNote(strings.common.error(err instanceof Error ? err.message : String(err)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="panel add-form" onSubmit={(e) => void submit(e)}>
      <h2>{strings.connections.t212Title}</h2>
      <p className="muted">{strings.connections.t212Hint}</p>
      <label>
        <span>{strings.connections.keyId}</span>
        <input
          required
          autoComplete="off"
          value={keyId}
          onChange={(e) => {
            setKeyId(e.target.value);
          }}
        />
      </label>
      <label>
        <span>{strings.connections.secret}</span>
        <input
          required
          type="password"
          autoComplete="off"
          value={secret}
          onChange={(e) => {
            setSecret(e.target.value);
          }}
        />
      </label>
      <label>
        <span>{strings.connections.accountName}</span>
        <input
          required
          value={accountName}
          placeholder={tips.namePlaceholder}
          onChange={(e) => {
            setAccountName(e.target.value);
          }}
        />
      </label>
      <label>
        <span>{strings.connections.accountKind}</span>
        <select
          value={accountKind}
          onChange={(e) => {
            const k = T212_KINDS.find((x) => x === e.target.value);
            if (k) setAccountKind(k);
          }}
        >
          {T212_KINDS.map((k) => (
            <option key={k} value={k}>
              {strings.kinds[k]}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        {busy ? strings.connections.connecting : strings.connections.connect}
      </button>
      {note ? <p className="muted">{note}</p> : null}
    </form>
  );
}

function ConnectBank() {
  const status = useAsync(() => api.ebStatus(), 'eb-status');
  const configured = status.data?.configured === true;
  const banks = useAsync(
    () => (configured ? api.ebBanks() : Promise.resolve([])),
    `eb-banks-${String(configured)}`,
  );
  const [query, setQuery] = useState('');
  const [going, setGoing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (banks.data ?? [])
      .filter((b) => q === '' || b.name.toLowerCase().includes(q))
      .slice(0, 30);
  }, [banks.data, query]);

  const go = async (name: string) => {
    setGoing(name);
    setError(null);
    try {
      const { url } = await api.ebStart(name);
      console.info('dewidebug open banking consent starting', { name });
      window.location.assign(url);
    } catch (err) {
      setError(strings.common.error(err instanceof Error ? err.message : String(err)));
      setGoing(null);
    }
  };

  return (
    <div className="panel">
      <h2>{strings.connections.bankTitle}</h2>
      <p className="muted">{strings.connections.bankHint}</p>
      {status.data && !configured ? (
        <p className="muted">{strings.connections.bankNotSetUp}</p>
      ) : null}
      {configured ? (
        <>
          <label className="bank-search">
            <span>{strings.connections.bankSearch}</span>
            <input
              value={query}
              placeholder={tips.bankSearch}
              onChange={(e) => {
                setQuery(e.target.value);
              }}
            />
          </label>
          {banks.loading ? <p className="muted">{strings.connections.bankLoading}</p> : null}
          <ul className="bank-list">
            {shown.map((b) => (
              <li key={`${b.country}-${b.name}`}>
                <span>{b.name}</span>
                <button type="button" disabled={going !== null} onClick={() => void go(b.name)}>
                  {going === b.name ? strings.connections.bankGoing : strings.connections.bankGo}
                </button>
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {error ? <p className="error">{error}</p> : null}
    </div>
  );
}

function ReturnNote() {
  const params = new URLSearchParams(useSearch());
  const connected = params.get('connected');
  const failed = params.get('failed');
  if (connected) {
    return (
      <p className="note-ok">
        {params.get('renewed') === '1'
          ? strings.connections.backRenewed(connected)
          : strings.connections.backConnected(connected)}
      </p>
    );
  }
  if (failed) return <p className="error">{strings.connections.backFailed(failed)}</p>;
  return null;
}

export function ConnectionsPage({ onChange }: { onChange: () => void }) {
  const { data, error, reload } = useAsync(() => api.connections(), 'connections');
  const changed = () => {
    reload();
    onChange();
  };
  const remove = async (id: string) => {
    if (!window.confirm(strings.connections.confirmRemove)) return;
    await api.disconnect(id);
    console.info('dewidebug connection removed', { id });
    changed();
  };
  return (
    <section>
      <h1>{strings.connections.title}</h1>
      <ReturnNote />
      <p className="muted">{strings.connections.hint}</p>
      {error ? <p className="error">{strings.common.error(error)}</p> : null}
      {data?.length === 0 ? <p className="muted">{strings.connections.none}</p> : null}
      <ul className="account-list">
        {(data ?? []).map((c) => (
          <li key={c.id} className="account-row">
            <div className="account-main">
              <b>{c.label}</b>
              <span className="muted">{strings.sources[c.kind]}</span>
              <button type="button" className="danger" onClick={() => void remove(c.id)}>
                {strings.connections.remove}
              </button>
            </div>
          </li>
        ))}
      </ul>
      <ConnectBank />
      <AddTrading212 onAdded={changed} />
    </section>
  );
}
