import type {
  AccountKind,
  Cadence,
  Category,
  HealthReason,
  HealthState,
  Owner,
  SourceKind,
} from '@aerarium/shared';

export const names = {
  app: 'Aerarium',
} as const;

export const strings = {
  nav: {
    dashboard: '🏛️ Overview',
    accounts: '🗂️ Accounts',
    transactions: '🧾 Transactions',
    outgoings: '📤 Outgoings',
    reports: '📊 Reports',
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
    snapshot: (day: string) => `💾 Nightly backup copy ${day}`,
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
    monzoTitle: '🏦 Monzo',
    monzoNeedsUrl: '⚙️ Monzo needs the server’s public address (PUBLIC_URL) set.',
    monzoSetupHint:
      '💡 One-off: at developers.monzo.com create a Confidential OAuth client with this redirect URL, then paste its client ID and secret here (stored encrypted).',
    monzoRedirect: (url: string) => `🔗 Redirect URL: ${url}`,
    clientId: '🆔 Client ID',
    clientSecret: '🔑 Client secret',
    save: '💾 Save',
    saving: '⏳ Saving…',
    monzoConnect: '➡️ Connect Monzo',
    monzoGoing: '⏳ Opening Monzo…',
    monzoApprove:
      '📱 Now approve Aerarium in the Monzo app (a notification asks once), then press the button below within 5 minutes: Monzo only shares your full history in that window.',
    monzoApproved: '✅ I’ve approved it: sync now',
    monzoSyncing: '⏳ First sync running, see 🩺 Health',
  },
  transactions: {
    title: '🧾 Transactions',
    search: '🔎 Search',
    allAccounts: '🗂️ All accounts',
    allCategories: '🏷️ All categories',
    none: '📭 No transactions match.',
    loading: '⏳ Loading transactions…',
    transfer: '🔁 Between your accounts',
    showing: (n: number) => `📄 Showing the latest ${String(n)}`,
  },
  outgoings: {
    title: '📤 Outgoings',
    monthly: '📅 Regular payments a month',
    jointPart: (amount: string) => `👥 of which from joint accounts: ${amount}`,
    dueSoon: '⏰ Due in the next two weeks',
    nothingDue: '✅ Nothing due in the next two weeks.',
    all: '🔁 Regular payments found',
    none: '📭 No regular payments found yet: they appear once a payee has been paid a few times on a regular rhythm.',
    lapsed: '💤 Stopped',
    next: (date: string) => `📅 Next about ${date}`,
    last: (date: string) => `🕒 Last ${date}`,
    rise: (pct: number, from: string, to: string) => `⚠️ Up ${String(pct)}%: ${from} → ${to}`,
    fall: (pct: number, from: string, to: string) =>
      `📉 Down ${String(Math.abs(pct))}%: ${from} → ${to}`,
    perMonth: (amount: string) => `📆 ${amount} a month`,
    times: (n: number) => `🔢 Seen ${String(n)} times`,
    loading: '⏳ Working out your regular payments…',
  },
  reports: {
    title: '📊 Weekly reports',
    makeNow: '📊 Make last week’s report now',
    making: '⏳ Working it out…',
    none: '📭 No reports yet: the first arrives on Monday morning, or make one now.',
    week: (start: string, end: string) => `🗓️ ${start} to ${end}`,
    netWorth: '🏠 Net worth change',
    unknown: '❔ Not known yet (the daily net worth record starts with this app)',
    whereFrom: '🧭 Where the change came from',
    saved: '💼 Saved (in minus out)',
    markets: '📈 Markets (investments, after deposits)',
    interest: '💰 Interest',
    debt: '💳 Debt paid down',
    unexplained: '❔ Not explained (typed-in balances, timing)',
    spending: '🛒 Spending',
    usual: (amount: string) => `📏 Usual week: ${amount}`,
    topCats: '🏷️ Biggest categories this week',
    year: '📅 The year',
    ytd: '📈 Since 1 January',
    projection: '🔭 At this rate, in 12 months',
    savingsRate: '💯 Savings rate this week',
    psa: '🧾 Interest outside ISAs this tax year',
    psaOf: (used: string, allowance: string) =>
      `🧾 ${used} of your ${allowance} Personal Savings Allowance`,
    older: '🗂️ Earlier weeks',
  },
  cadences: {
    weekly: '🗓️ Weekly',
    monthly: '🗓️ Monthly',
    quarterly: '🗓️ Quarterly',
    yearly: '🗓️ Yearly',
  } satisfies Record<Cadence, string>,
  categories: {
    income: '💼 Income',
    interest: '💰 Interest',
    transfer: '🔁 Transfer',
    housing: '🏠 Housing',
    energy: '⚡ Energy',
    water: '💧 Water',
    telecoms: '📶 Broadband & mobile',
    'council-tax': '🏛️ Council tax',
    insurance: '🛡️ Insurance',
    childcare: '👶 Childcare',
    groceries: '🛒 Groceries',
    'eating-out': '🍽️ Eating out',
    transport: '🚆 Transport',
    shopping: '🛍️ Shopping',
    subscriptions: '🔁 Subscriptions',
    cash: '💵 Cash',
    fees: '🧾 Fees',
    other: '📦 Other',
  } satisfies Record<Category, string>,
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
    case 'snapshot-stale':
      return r.lastDay
        ? `💾 Last nightly backup copy was ${r.lastDay}`
        : '💾 No nightly backup copy yet';
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
  txSearch: 'Payee or description',
};

export const units = {
  catLine: (cat: string, amount: string, usual: string) => `${cat}: ${amount} (usually ${usual})`,
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
