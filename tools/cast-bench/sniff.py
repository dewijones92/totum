import socket, struct, time, base64
def q(name):
    out = b''.join(bytes([len(p)]) + p.encode() for p in name.split('.')) + b'\0'
    return struct.pack('>HHHHHH', 0, 0, 1, 0, 0, 0) + out + struct.pack('>HH', 12, 1)
raw = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.ntohs(0x0800)); raw.bind(('eth0', 0)); raw.settimeout(0.5)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton('192.168.0.19'))
for t in ('TV_IP', '224.0.0.251'): s.sendto(q('_googlecast._tcp.local'), (t, 5353))
best = b''; end = time.time() + 8
while time.time() < end:
    try: pkt = raw.recv(65535)[14:]
    except socket.timeout: continue
    ihl = (pkt[0] & 15) * 4
    if pkt[9] != 17 or socket.inet_ntoa(pkt[12:16]) != 'TV_IP': continue
    if struct.unpack('>H', pkt[ihl:ihl+2])[0] != 5353: continue
    pl = pkt[ihl+8:]
    if pl[2] & 0x80 and b'googlecast' in pl and len(pl) > len(best): best = pl
print(base64.b64encode(best).decode())
