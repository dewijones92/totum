import { formatMoney, KeyDateKind, money, parseMajorToMinor } from '@aerarium/shared';
import { useState, type SyntheticEvent } from 'react';
import { api } from '../api/client.ts';
import { strings, tips } from '../strings.ts';
import { useAsync } from '../ui/use-async.ts';
import { ReminderList } from './ReminderLine.tsx';

const gbp = (minor: number) => formatMoney(money(minor));
const today = () => new Date().toISOString().slice(0, 10);

export function RemindersPage() {
  const nudges = useAsync(() => api.nudges(), 'nudges');
  const dates = useAsync(() => api.keyDates(), 'key-dates');
  const [title, setTitle] = useState('');
  const [kind, setKind] = useState<KeyDateKind>('renewal');
  const [date, setDate] = useState(today);
  const [remindDays, setRemindDays] = useState('28');
  const [isaAmount, setIsaAmount] = useState('');
  const [isaDate, setIsaDate] = useState(today);
  const [isaNote, setIsaNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const refresh = () => {
    nudges.reload();
    dates.reload();
  };
  const run = async (work: () => Promise<unknown>, what: string) => {
    setError(null);
    try {
      await work();
      console.info('dewidebug reminders action', { what });
      refresh();
    } catch (err) {
      setError(strings.common.error(err instanceof Error ? err.message : String(err)));
    }
  };
  const addDate = (e: SyntheticEvent) => {
    e.preventDefault();
    void run(
      () => api.addKeyDate({ title, kind, date, remindDays: Number(remindDays) || 28 }),
      'add-date',
    ).then(() => {
      setTitle('');
    });
  };
  const addIsa = (e: SyntheticEvent) => {
    e.preventDefault();
    const amountMinor = parseMajorToMinor(isaAmount);
    if (amountMinor === null || amountMinor <= 0) {
      setError(strings.accounts.badAmount);
      return;
    }
    void run(() => api.addIsa({ date: isaDate, amountMinor, note: isaNote }), 'add-isa').then(
      () => {
        setIsaAmount('');
        setIsaNote('');
      },
    );
  };

  const isa = nudges.data?.isa;
  const pct = isa ? Math.min(100, Math.round((isa.usedMinor / isa.allowanceMinor) * 100)) : 0;
  return (
    <section>
      <h1>{strings.reminders.title}</h1>
      {error ? <p className="error">{error}</p> : null}
      <h2>{strings.reminders.comingUp}</h2>
      {nudges.data ? <ReminderList items={nudges.data.reminders} /> : null}

      {isa ? (
        <div className="panel" data-testid="isa">
          <h2>{strings.reminders.isaTitle}</h2>
          <div className="meter" aria-hidden="true">
            <div className="meter-fill" style={{ width: `${String(pct)}%` }} />
          </div>
          <p>
            <b>{strings.reminders.isaUsed(gbp(isa.usedMinor), gbp(isa.allowanceMinor))}</b>
          </p>
          <p>{strings.reminders.isaLeft(gbp(isa.leftMinor), isa.daysLeft)}</p>
          {isa.cashCapMinor !== null ? (
            <p className="muted">{strings.reminders.isaCashCap(gbp(isa.cashCapMinor))}</p>
          ) : null}
          {isa.manual.length > 0 ? (
            <>
              <p className="muted">{strings.reminders.isaManual}</p>
              <ul className="plain-list">
                {isa.manual.map((m) => (
                  <li key={m.id}>
                    <span>
                      {m.date} · {gbp(m.amountMinor)} {m.note}
                    </span>
                    <button
                      type="button"
                      className="danger"
                      onClick={() => void run(() => api.removeIsa(m.id), 'remove-isa')}
                    >
                      {strings.reminders.remove}
                    </button>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
          <form className="add-form" onSubmit={addIsa}>
            <p className="muted">{strings.reminders.isaAdd}</p>
            <label>
              <span>{strings.reminders.isaAmount}</span>
              <input
                required
                inputMode="decimal"
                value={isaAmount}
                placeholder={tips.amountPlaceholder}
                onChange={(e) => {
                  setIsaAmount(e.target.value);
                }}
              />
            </label>
            <label>
              <span>{strings.reminders.isaDate}</span>
              <input
                required
                type="date"
                value={isaDate}
                onChange={(e) => {
                  setIsaDate(e.target.value);
                }}
              />
            </label>
            <label>
              <span>{strings.reminders.isaNote}</span>
              <input
                value={isaNote}
                onChange={(e) => {
                  setIsaNote(e.target.value);
                }}
              />
            </label>
            <button type="submit">{strings.reminders.add}</button>
          </form>
        </div>
      ) : null}

      <div className="panel">
        <h2>{strings.reminders.dates}</h2>
        <p className="muted">{strings.reminders.datesHint}</p>
        {dates.data?.length === 0 ? <p className="muted">{strings.reminders.noDates}</p> : null}
        <ul className="plain-list">
          {(dates.data ?? []).map((k) => (
            <li key={k.id} data-testid="key-date">
              <span>
                {strings.reminders.kinds[k.kind]} · {k.title} · {k.date}
              </span>
              <button
                type="button"
                className="danger"
                onClick={() => void run(() => api.removeKeyDate(k.id), 'remove-date')}
              >
                {strings.reminders.remove}
              </button>
            </li>
          ))}
        </ul>
        <form className="add-form" onSubmit={addDate}>
          <p className="muted">{strings.reminders.addDate}</p>
          <label>
            <span>{strings.reminders.dateTitle}</span>
            <input
              required
              value={title}
              placeholder={tips.dateTitlePlaceholder}
              onChange={(e) => {
                setTitle(e.target.value);
              }}
            />
          </label>
          <label>
            <span>{strings.reminders.dateKind}</span>
            <select
              value={kind}
              onChange={(e) => {
                setKind(KeyDateKind.parse(e.target.value));
              }}
            >
              {KeyDateKind.options.map((k) => (
                <option key={k} value={k}>
                  {strings.reminders.kinds[k]}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>{strings.reminders.dateWhen}</span>
            <input
              required
              type="date"
              value={date}
              onChange={(e) => {
                setDate(e.target.value);
              }}
            />
          </label>
          <label>
            <span>{strings.reminders.remindDays}</span>
            <input
              inputMode="numeric"
              value={remindDays}
              onChange={(e) => {
                setRemindDays(e.target.value);
              }}
            />
          </label>
          <button type="submit">{strings.reminders.add}</button>
        </form>
      </div>
    </section>
  );
}
