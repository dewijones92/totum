import type { AccountKind, HealthReason, HealthState, Owner, SourceKind } from '@aerarium/shared';

export const names = {
  app: 'Aerarium',
} as const;

export const strings = {
  nav: {
    dashboard: '🏛️ Overview',
    accounts: '🗂️ Accounts',
    connections: '🔌 Connections',
    health: '🩺 Health',
  },
  health: {
    title: '🩺 Health',
    barFail: (n: number) =>
      n === 1 ? '🔴 1 source needs you:' : `🔴 ${String(n)} sources need you:`,
    barWarn: (n: number) =>
      n === 1 ? '🟠 1 thing to look at:' : `🟠 ${String(n)} things to look at:`,
    open: '🩺 Open health',
    sources: '🔌 Connected sources',
    manual: '✍️ Balances you type in',
    app: '🖥️ The app itself',
    noSources: '📭 No banks or providers connected yet.',
    noManual: '📭 No typed-in balances yet.',
    syncNow: '🔄 Sync now',
    reconnect: '🔑 Reconnect',
    syncing: '⏳ Syncing…',
    syncBusy: '⏳ Already syncing',
    syncStarted: '✅ Sync started',
    runs: '📜 Recent runs',
    lastOk: (when: string) => `✅ Last good sync ${when}`,
    neverOk: '⚠️ Never synced successfully',
    consentUntil: (when: string) => `🔑 Consent until ${when}`,
    scheduler: (when: string) => `⏱️ Scheduler ticked ${when}`,
    schedulerNever: '⚠️ Scheduler has not ticked yet',
    version: (v: string) => `🏷️ Version ${v}`,
    database: (size: string) => `🗄️ Database ${size}`,
    asOf: (when: string) => `📅 Balance as of ${when}`,
    runOk: (accounts: number, added: number) =>
      `📦 ${String(accounts)} accounts, ${String(added)} new transactions`,
    state: {
      ok: '🟢 Healthy',
      warn: '🟠 Needs a look',
      fail: '🔴 Broken',
    } satisfies Record<HealthState, string>,
    outcome: { ok: '✅', failed: '❌', running: '⏳' },
  },
  dashboard: {
    title: '🏛️ Overview',
    household: '🏠 Household net worth',
    yours: '👤 Yours (half of joint)',
    assets: '📈 Assets',
    liabilities: '📉 Owed',
    leftOut: (n: number) =>
      `ℹ️ ${String(n)} account${n === 1 ? '' : 's'} not counted (excluded, no balance yet, or another currency)`,
    empty: '📭 Nothing here yet. Add an account to get started.',
    unconfirmed: (n: number) =>
      n === 1
        ? '📥 1 new account: say whose it is'
        : `📥 ${String(n)} new accounts: say whose they are`,
    addFirst: '➕ Add an account',
    history: '📊 Net worth over time',
    historyTooShort: '⏳ The chart fills in day by day.',
    loading: '⏳ Loading…',
  },
  accounts: {
    title: '🗂️ Accounts',
    addTitle: '➕ Add a balance you type in',
    addHint:
      '💡 For savings, pensions, the mortgage or anything without a connection. Aerarium reminds you when a balance gets old.',
    name: '🏷️ Name',
    kind: '🗃️ Type',
    owner: '👥 Whose',
    balance: '💷 Balance',
    owed: '💷 Amount owed',
    asOf: '📅 As of',
    add: '✅ Add',
    adding: '⏳ Adding…',
    update: '💾 Save balance',
    updating: '⏳ Saving…',
    remove: '🗑️ Remove',
    confirmRemove: '⚠️ Remove this account and its history?',
    counted: '✅ Counted in net worth',
    notCounted: '🚫 Not counted',
    badAmount: '⚠️ Type an amount like 1234.56',
    synced: '🔌 Synced automatically',
    typedIn: '✍️ Typed in',
    joint: '👥 Joint',
    whose: '📥 New account: whose is it?',
    markMine: '👤 Mine',
    markJoint: '👥 Joint',
  },
  connections: {
    title: '🔌 Connections',
    hint: '🔒 Everything here is read-only: Aerarium can look, never move money.',
    none: '📭 Nothing connected yet.',
    remove: '🗑️ Disconnect',
    confirmRemove: '⚠️ Disconnect this and remove its accounts and history from Aerarium?',
    t212Title: '📈 Add Trading 212',
    t212Hint:
      '💡 In the Trading 212 app: Settings → API → generate a key with read-only permissions. Paste its key ID and secret here; they are stored encrypted.',
    keyId: '🆔 API key ID',
    secret: '🔑 API secret',
    accountName: '🏷️ Name',
    accountKind: '🗃️ Type',
    connect: '🔌 Connect',
    connecting: '⏳ Connecting…',
    connected: '✅ Connected: the first sync is running, see 🩺 Health',
    bankTitle: '🏦 Connect a bank or card (Open Banking)',
    bankHint:
      '🔒 Read-only access through Enable Banking: balances and transactions, never payments. You approve it in your bank’s app, and renew it every 90 days (Aerarium reminds you).',
    bankNotSetUp:
      '⚙️ Open Banking is not set up on the server yet: it needs the Enable Banking key.',
    bankSearch: '🔎 Find your bank',
    bankGo: '➡️ Connect',
    bankGoing: '⏳ Opening your bank…',
    bankLoading: '⏳ Loading banks…',
    backConnected: (name: string) => `✅ ${name} connected: first sync running`,
    backRenewed: (name: string) => `✅ ${name} consent renewed for another 90 days`,
    backFailed: (why: string) => `❌ Not connected: ${why}`,
  },
  common: {
    error: (message: string) => `❌ ${message}`,
    retry: '🔄 Try again',
  },
  kinds: {
    current: '🏦 Current account',
    savings: '💰 Savings',
    'isa-cash': '🛡️ Cash ISA',
    'isa-stocks': '📈 Stocks & shares ISA',
    investment: '📈 Investments',
    pension: '👴 Pension',
    'credit-card': '💳 Credit card',
    mortgage: '🏠 Mortgage',
    loan: '🧾 Loan',
    childcare: '👶 Childcare account',
    other: '📦 Other',
  } satisfies Record<AccountKind, string>,
  owners: {
    mine: '👤 Mine',
    joint: '👥 Joint',
  } satisfies Record<Owner, string>,
  sources: {
    manual: '✍️ Typed in',
    'enable-banking': '🏦 Open Banking',
    monzo: '🏦 Monzo',
    trading212: '📈 Trading 212',
    fake: '🧪 Fake bank',
  } satisfies Record<SourceKind, string>,
};

export function reasonText(r: HealthReason): string {
  switch (r.code) {
    case 'healthy':
      return '✅ All good';
    case 'running':
      return '⏳ Syncing now';
    case 'never-synced':
      return '⏳ Waiting for its first sync';
    case 'last-run-failed':
      return `❌ Last sync failed: ${r.error}`;
    case 'stale':
      return `⌛ No good sync for ${String(r.hours)} hours`;
    case 'consent-expired':
      return '🔑 Bank consent has expired: reconnect it';
    case 'consent-expiring':
      return `🔑 Bank consent expires in ${String(r.days)} day${r.days === 1 ? '' : 's'}`;
    case 'manual-stale':
      return `⌛ Balance is ${String(r.days)} days old: update it`;
    case 'manual-missing':
      return '✍️ No balance entered yet';
    case 'scheduler-silent':
      return r.minutes < 0
        ? '🛑 The scheduler has not started'
        : `🛑 The scheduler has been silent for ${String(r.minutes)} minutes`;
  }
}

export const tips = {
  nav: 'Main sections',
  amountPlaceholder: '1234.56',
  namePlaceholder: 'e.g. Easy access saver',
  history: 'Net worth over time',
  bankSearch: 'Bank name',
};

export const units = {
  runLine: (outcome: string, when: string, detail: string) => `${outcome} ${when} · ${detail}`,
  ago: (ms: number): string => {
    const m = Math.round(ms / 60_000);
    if (m < 1) return 'just now';
    if (m < 60) return `${String(m)} min ago`;
    const h = Math.round(m / 60);
    if (h < 48) return `${String(h)} h ago`;
    return `${String(Math.round(h / 24))} days ago`;
  },
  bytes: (n: number): string =>
    n < 1024 * 1024 ? `${String(Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`,
};
