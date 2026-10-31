import type { HealthReport, SourceHealth } from '@aerarium/shared';
import { useState } from 'react';
import { api } from '../api/client.ts';
import { reasonText, strings, units } from '../strings.ts';
import type { AsyncState } from '../ui/use-async.ts';
import { useAsync } from '../ui/use-async.ts';

const ago = (iso: string) => units.ago(Date.now() - Date.parse(iso));

function Runs({ id }: { id: string }) {
  const { data } = useAsync(() => api.runs(id), `runs-${id}`);
  if (!data || data.length === 0) return null;
  return (
    <details>
      <summary>{strings.health.runs}</summary>
      <ul className="runs">
        {data.slice(0, 10).map((r) => (
          <li key={r.id}>
            {units.runLine(
              strings.health.outcome[r.outcome],
              ago(r.startedAt),
              r.outcome === 'failed'
                ? (r.error ?? '')
                : strings.health.runOk(r.accountsSeen, r.transactionsAdded),
            )}
          </li>
        ))}
      </ul>
    </details>
  );
}

function SourceTile({ s, onSynced }: { s: SourceHealth; onSynced: () => void }) {
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const sync = async () => {
    setBusy(true);
    try {
      const r = await api.syncNow(s.connectionId);
      setNote(r.outcome === 'busy' ? strings.health.syncBusy : strings.health.syncStarted);
      console.info('dewidebug sync now asked', { id: s.connectionId, outcome: r.outcome });
      setTimeout(onSynced, 1500);
    } catch (err) {
      setNote(strings.common.error(err instanceof Error ? err.message : String(err)));
    } finally {
      setBusy(false);
    }
  };
  const reconnect = async () => {
    setBusy(true);
    try {
      const { url } = await api.reconnect(s.connectionId);
      console.info('dewidebug reconnect starting', { id: s.connectionId });
      window.location.assign(url);
    } catch (err) {
      setNote(strings.common.error(err instanceof Error ? err.message : String(err)));
      setBusy(false);
    }
  };
  return (
    <article className={`tile tile-${s.state}`} data-testid={`source-${s.label}`}>
      <header>
        <b>{s.label}</b>
        <span>{strings.health.state[s.state]}</span>
      </header>
      <ul>
        {s.reasons.map((r, i) => (
          <li key={i}>{reasonText(r)}</li>
        ))}
        <li>{s.lastOkAt ? strings.health.lastOk(ago(s.lastOkAt)) : strings.health.neverOk}</li>
        {s.consentExpiresAt ? (
          <li>{strings.health.consentUntil(s.consentExpiresAt.slice(0, 10))}</li>
        ) : null}
      </ul>
      <div className="tile-actions">
        <button type="button" className="secondary" disabled={busy} onClick={() => void sync()}>
          {busy ? strings.health.syncing : strings.health.syncNow}
        </button>
        {s.kind === 'enable-banking' ? (
          <button type="button" disabled={busy} onClick={() => void reconnect()}>
            {strings.health.reconnect}
          </button>
        ) : null}
      </div>
      {note ? <p className="muted">{note}</p> : null}
      <Runs id={s.connectionId} />
    </article>
  );
}

export function HealthPage({ health }: { health: AsyncState<HealthReport> }) {
  const report = health.data;
  if (health.error) return <p className="error">{strings.common.error(health.error)}</p>;
  if (!report) return null;
  return (
    <section>
      <h1>{strings.health.title}</h1>
      <h2>{strings.health.sources}</h2>
      {report.sources.length === 0 ? <p className="muted">{strings.health.noSources}</p> : null}
      <div className="tiles">
        {report.sources.map((s) => (
          <SourceTile key={s.connectionId} s={s} onSynced={health.reload} />
        ))}
      </div>
      <h2>{strings.health.manual}</h2>
      {report.manual.length === 0 ? <p className="muted">{strings.health.noManual}</p> : null}
      <div className="tiles">
        {report.manual.map((m) => (
          <article key={m.accountId} className={`tile tile-${m.state}`}>
            <header>
              <b>{m.name}</b>
              <span>{strings.health.state[m.state]}</span>
            </header>
            <ul>
              {m.reasons.map((r, i) => (
                <li key={i}>{reasonText(r)}</li>
              ))}
            </ul>
          </article>
        ))}
      </div>
      <h2>{strings.health.app}</h2>
      <article className={`tile tile-${report.app.state} tile-wide`}>
        <header>
          <b>{strings.health.app}</b>
          <span>{strings.health.state[report.app.state]}</span>
        </header>
        <ul>
          {report.app.reasons.map((r, i) => (
            <li key={i}>{reasonText(r)}</li>
          ))}
          <li>
            {report.app.schedulerLastTickAt
              ? strings.health.scheduler(ago(report.app.schedulerLastTickAt))
              : strings.health.schedulerNever}
          </li>
          <li>{strings.health.version(report.app.version)}</li>
          <li>{strings.health.database(units.bytes(report.app.databaseBytes))}</li>
          {report.app.lastSnapshotDay ? (
            <li>{strings.health.snapshot(report.app.lastSnapshotDay)}</li>
          ) : null}
        </ul>
      </article>
    </section>
  );
}
