function ipv4ToInt(ip: string): number | null {
  const parts = ip.split('.');
  if (parts.length !== 4) return null;
  let value = 0;
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null;
    const n = Number(part);
    if (n > 255) return null;
    value = value * 256 + n;
  }
  return value;
}

function normalise(ip: string): string {
  const lower = ip.toLowerCase();
  return lower.startsWith('::ffff:') && lower.includes('.') ? lower.slice(7) : lower;
}

export function ipInCidr(ip: string, cidr: string): boolean {
  const [range = '', bitsText] = cidr.split('/');
  const address = normalise(ip);
  const base = normalise(range);
  const ipInt = ipv4ToInt(address);
  const baseInt = ipv4ToInt(base);
  if (ipInt !== null && baseInt !== null) {
    const bits = bitsText === undefined ? 32 : Number(bitsText);
    if (!Number.isInteger(bits) || bits < 0 || bits > 32) return false;
    if (bits === 0) return true;
    const mask = (0xffffffff << (32 - bits)) >>> 0;
    return (ipInt & mask) >>> 0 === (baseInt & mask) >>> 0;
  }
  if (ipInt === null && baseInt === null) {
    if (bitsText !== undefined && bitsText !== '128') return false;
    return address === base;
  }
  return false;
}

export function isTrusted(ip: string | undefined, cidrs: readonly string[]): boolean {
  if (!ip) return false;
  return cidrs.some((c) => ipInCidr(ip, c));
}
