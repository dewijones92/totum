import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { basename } from 'node:path';

const started = performance.now();
const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard'], {
  encoding: 'utf8',
})
  .split('\n')
  .filter(Boolean)
  .filter((f) => existsSync(f));
const read = (f) => readFileSync(f, 'utf8');
const failures = [];
const fail = (check, detail) => failures.push(`${check}: ${detail}`);

for (const f of files.filter((f) => /\.(test|spec)\.tsx?$/.test(f))) {
  if (/\b(it|test|describe)\.only\(/.test(read(f))) fail('focused test', `${f} contains .only(`);
}

const nvmrc = read('.nvmrc').trim();
for (const match of (existsSync('Dockerfile') ? read('Dockerfile') : '').matchAll(
  /^FROM node:(\d+)/gm,
)) {
  if (match[1] !== nvmrc)
    fail('node version', `Dockerfile uses node:${match[1]} but .nvmrc says ${nvmrc}`);
}

if (
  existsSync('pnpm-lock.yaml') &&
  /pkgs\.dev\.azure\.com|_authToken|_password/.test(read('pnpm-lock.yaml'))
) {
  fail('lockfile', 'pnpm-lock.yaml mentions a private registry or credential');
}

const secretPatterns = [
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/,
  /\bghp_[A-Za-z0-9]{30,}/,
  /\bgithub_pat_[A-Za-z0-9_]{30,}/,
];
for (const f of files.filter((f) => !/\.(png|pdf|wav|mp3|woff2?)$/.test(f))) {
  let text;
  try {
    text = read(f);
  } catch {
    continue;
  }
  if (secretPatterns.some((p) => p.test(text)))
    fail('secret', `${f} looks like it contains a credential`);
}

const docs = files.filter((f) => f.startsWith('docs/') && f.endsWith('.md'));
for (const f of docs) {
  const text = read(f);
  if (!/^---\n(?:[\s\S]*?\n)?updated: \d{4}-\d{2}-\d{2}\n(?:[\s\S]*?\n)?---\n/.test(text)) {
    fail('doc frontmatter', `${f} needs YAML frontmatter with an "updated: YYYY-MM-DD" line`);
  }
}

const adrIndex = read('docs/adr/_index.md');
for (const f of docs.filter((f) => /^docs\/adr\/\d{4}-.+\.md$/.test(f))) {
  if (!adrIndex.includes(basename(f)))
    fail('ADR index', `${f} is not linked from docs/adr/_index.md`);
}
const featureIndex = read('docs/features/_index.md');
for (const f of docs.filter((f) => f.startsWith('docs/features/') && !f.endsWith('_index.md'))) {
  if (!featureIndex.includes(basename(f)))
    fail('feature index', `${f} is not linked from docs/features/_index.md`);
}

const specs = files.filter((f) => /^e2e\/[^/]+\.spec\.ts$/.test(f)).map((f) => basename(f));
const testsIndex = read('docs/tests/_index.md');
for (const spec of specs) {
  if (!testsIndex.includes(spec))
    fail('test map', `e2e/${spec} is not described in docs/tests/_index.md`);
}

const rootClaude = existsSync('../CLAUDE.md') ? read('../CLAUDE.md') : '';
if (!rootClaude.includes('aerarium/')) {
  fail('two-app map', 'the repo root CLAUDE.md must keep its map pointing agents at aerarium/');
}

const ms = Math.round(performance.now() - started);
if (failures.length > 0) {
  console.error(`preflight: ${String(failures.length)} problem(s) in ${String(ms)} ms`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.info(`preflight: ok (${String(files.length)} files, ${String(ms)} ms)`);
