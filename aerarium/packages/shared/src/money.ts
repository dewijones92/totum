import { z } from 'zod';

export const Currency = z.string().regex(/^[A-Z]{3}$/);
export type Currency = z.infer<typeof Currency>;

export const Minor = z.number().int().brand<'Minor'>();
export type Minor = z.infer<typeof Minor>;

export const Money = z.object({ minor: Minor, currency: Currency });
export type Money = z.infer<typeof Money>;

export const minor = (n: number): Minor => Minor.parse(n);

export function money(n: number, currency: Currency = 'GBP'): Money {
  return { minor: minor(n), currency };
}

export function addMinor(a: Minor, b: Minor): Minor {
  return minor(a + b);
}

const formatters = new Map<string, Intl.NumberFormat>();

export function formatMoney(m: Money): string {
  let f = formatters.get(m.currency);
  if (!f) {
    f = new Intl.NumberFormat('en-GB', { style: 'currency', currency: m.currency });
    formatters.set(m.currency, f);
  }
  return f.format(m.minor / 100);
}

export function parseMajorToMinor(text: string): Minor | null {
  const cleaned = text.replace(/[£,\s]/g, '');
  if (!/^-?\d+(\.\d{1,2})?$/.test(cleaned)) return null;
  const negative = cleaned.startsWith('-');
  const [whole = '0', frac = ''] = cleaned.replace('-', '').split('.');
  const value = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return minor(negative ? -value : value);
}
