import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const ALLOWED_EMAIL_DOMAINS = [
  'example.com',
  'example.org',
  'users.noreply.github.com',
  'anthropic.com',
];

function luhn(digits) {
  let sum = 0;
  for (let i = 0; i < digits.length; i++) {
    let d = Number(digits[digits.length - 1 - i]);
    if (i % 2 === 1) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return sum % 10 === 0;
}

const PATTERNS = [
  { name: 'sort code', re: /\b\d{2}-\d{2}-\d{2}\b/g },
  { name: 'IBAN', re: /\bGB\d{2}\s?[A-Z]{4}(?:\s?\d){14}\b/g },
  { name: 'UK mobile number', re: /(?<![\d.])(?:\+44\s?7|07)\d{3}\s?\d{6}\b/g },
  {
    name: 'card number',
    re: /\b(?:\d[ -]?){15,18}\d\b/g,
    accept: (m) => luhn(m.replace(/\D/g, '')),
  },
  {
    name: 'email address',
    re: /\b[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b/g,
    accept: (_m, domain) => {
      const host = domain.toLowerCase();
      return !ALLOWED_EMAIL_DOMAINS.some((d) => host === d || host.endsWith(`.${d}`));
    },
  },
  { name: 'UK number plate', re: /\b[A-Z]{2}\d{2}\s?[A-Z]{3}\b/g },
];

export function loadDenylist(path) {
  if (!path || !existsSync(path)) return null;
  return readFileSync(path, 'utf8')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '' && !l.startsWith('#'));
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function scanText(file, text, denylist) {
  const problems = [];
  const lines = text.split('\n');
  lines.forEach((line, i) => {
    for (const p of PATTERNS) {
      for (const m of line.matchAll(p.re)) {
        if (p.accept && !p.accept(m[0], m[1])) continue;
        problems.push(`${file}:${String(i + 1)}: looks like a ${p.name}`);
      }
    }
    for (const word of denylist ?? []) {
      if (new RegExp(`(?<![A-Za-z0-9])${escapeRe(word)}(?![A-Za-z0-9])`, 'i').test(line)) {
        problems.push(
          `${file}:${String(i + 1)}: contains a denylisted word (line ${String(denylist.indexOf(word) + 1)} of the denylist)`,
        );
      }
    }
  });
  return problems;
}

function main() {
  const started = performance.now();
  const denylistPath =
    process.env['AERARIUM_DENYLIST'] ?? join(homedir(), '.local/share/finance-hub/denylist.txt');
  const denylist = loadDenylist(denylistPath);
  const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard'], {
    encoding: 'utf8',
  })
    .split('\n')
    .filter((f) => f !== '' && !/\.(png|jpg|ico|woff2?|pdf)$/.test(f) && f !== 'pnpm-lock.yaml')
    .filter((f) => existsSync(f));
  const problems = files.flatMap((f) => scanText(f, readFileSync(f, 'utf8'), denylist));
  const ms = Math.round(performance.now() - started);
  const mode = denylist
    ? `patterns + ${String(denylist.length)}-word denylist`
    : 'patterns only (no denylist on this machine)';
  if (problems.length > 0) {
    console.error(
      `privacy-guard: ${String(problems.length)} problem(s) in ${String(ms)} ms [${mode}]`,
    );
    for (const p of problems) console.error(`  ✗ ${p}`);
    console.error(
      'This repo is PUBLIC. Personal or financial data belongs in /var/lib/aerarium or ~/.local/share/finance-hub, never here.',
    );
    process.exit(1);
  }
  console.info(`privacy-guard: ok (${String(files.length)} files, ${String(ms)} ms) [${mode}]`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) main();
