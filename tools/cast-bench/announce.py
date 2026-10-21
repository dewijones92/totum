import struct, sys, base64
b = base64.b64decode(open(sys.argv[1]).read().strip())
if not b: sys.exit("no mDNS reply captured from the TV (is it on?)")
def name(o):
    parts = []; jump = None
    while True:
        l = b[o]
        if l == 0: o += 1; break
        if l & 0xC0 == 0xC0:
            if jump is None: jump = o + 2
            o = ((l & 0x3f) << 8) | b[o+1]; continue
        parts.append(b[o+1:o+1+l]); o += 1 + l
    return parts, (jump if jump is not None else o)
enc = lambda parts: b''.join(bytes([len(p)]) + p for p in parts) + b'\0'
qd, an, ns, ar = struct.unpack('>4H', b[4:12]); o = 12
for _ in range(qd): _, o = name(o); o += 4
recs = []
for _ in range(an + ns + ar):
    n, o = name(o); t, cl, ttl, rl = struct.unpack('>HHIH', b[o:o+10]); o += 10; rd = b[o:o+rl]
    if t == 12: rd = enc(name(o)[0])
    elif t == 33: rd = b[o:o+6] + enc(name(o+6)[0])
    o += rl; recs.append((enc(n), t, cl if t == 12 else cl | 0x8000, ttl, rd))
out = struct.pack('>6H', 0, 0x8400, 0, len(recs), 0, 0) + b''.join(n + struct.pack('>HHIH', t, cl, ttl, len(rd)) + rd for n, t, cl, ttl, rd in recs)
open(sys.argv[2], 'wb').write(out); print(f'{len(recs)} records, {len(out)} bytes')
