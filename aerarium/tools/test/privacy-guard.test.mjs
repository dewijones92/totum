import { describe, expect, it } from 'vitest';
import { scanText } from '../privacy-guard.mjs';

const at = (text, deny = null) => scanText('f.ts', text, deny);
const join = (...parts) => parts.join('');

describe('privacy guard', () => {
  it('passes ordinary code, dates and ISO times', () => {
    expect(at("const when = '2026-10-31T05:36:00Z'; const n = 120000;")).toEqual([]);
  });
  it('flags a sort code', () => {
    expect(at(join('sort: 12', '-34-', '56'))).toHaveLength(1);
  });
  it('flags a Luhn-valid card number but not a random long number', () => {
    expect(at(join('card 4111 1111', ' 1111 1111'))).toHaveLength(1);
    expect(at(join('id 41111111', '11111112'))).toEqual([]);
  });
  it('flags a real-looking email, allows example.com in any case', () => {
    expect(at(join('to: someone', '@', 'gmail.com'))).toHaveLength(1);
    expect(at('to: Owner@Example.com')).toEqual([]);
  });
  it('flags a UK mobile number and a number plate', () => {
    expect(at(join('call 07700', ' 900123'))).toHaveLength(1);
    expect(at(join("reg 'AB12", " CDE'"))).toHaveLength(1);
  });
  it('flags a denylisted word on a word boundary, case-insensitively', () => {
    expect(at('const bank = "Example Bank";', ['example bank'])).toHaveLength(1);
    expect(at('const x = "Examplebanking";', ['example bank'])).toEqual([]);
  });
  it('names the denylist line, never the word', () => {
    const [problem] = at('secretword here', ['other', 'secretword']);
    expect(problem).toContain('denylist entry 2');
    expect(problem).not.toContain('secretword');
  });
});

describe('addedLines', () => {
  it('reads only added lines, with their file and line number', async () => {
    const { addedLines } = await import('../privacy-guard.mjs');
    const diff = [
      'diff --git a/x.ts b/x.ts',
      '--- a/x.ts',
      '+++ b/x.ts',
      '@@ -1,0 +5,2 @@',
      '+const a = 1;',
      '+const b = 2;',
      'diff --git a/gone.ts b/gone.ts',
      '--- a/gone.ts',
      '+++ /dev/null',
      '@@ -1 +0,0 @@',
      '-removed',
    ].join('\n');
    expect(addedLines(diff)).toEqual([
      { file: 'x.ts', line: 5, text: 'const a = 1;' },
      { file: 'x.ts', line: 6, text: 'const b = 2;' },
    ]);
  });
});
