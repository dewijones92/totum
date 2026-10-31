import { useState, type SyntheticEvent } from 'react';
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
      <AddTrading212 onAdded={changed} />
    </section>
  );
}
