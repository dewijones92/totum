import { formatMoney, money, type Reminder } from '@aerarium/shared';
import { strings } from '../strings.ts';

export function reminderText(r: Reminder): string {
  switch (r.kind) {
    case 'key-date':
      return strings.reminders.keyDate(r.title, r.daysLeft, r.date);
    case 'consent':
      return strings.reminders.consent(r.label, r.daysLeft, r.date);
    case 'idle-cash':
      return strings.reminders.idle(formatMoney(money(r.minMinor)), r.name, r.days);
  }
}

export function ReminderList({ items }: { items: readonly Reminder[] }) {
  if (items.length === 0) return <p className="muted">{strings.reminders.nothing}</p>;
  return (
    <ul className="reminder-list">
      {items.map((r, i) => (
        <li key={i} className={`reminder reminder-${r.kind}`} data-testid="reminder">
          {reminderText(r)}
        </li>
      ))}
    </ul>
  );
}
