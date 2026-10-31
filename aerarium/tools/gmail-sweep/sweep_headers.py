import imaplib, json, sys, time, email, re
from email.header import decode_header, make_header
from email.utils import parseaddr, parsedate_to_datetime

CRED = "/var/lib/hermes-gmail/gmail.json"
CHUNK = 1500

def log(msg):
    sys.stderr.write(time.strftime("%H:%M:%S ") + msg + "\n"); sys.stderr.flush()

def dec(v):
    try:
        return str(make_header(decode_header(v or ""))).strip()
    except Exception:
        return (v or "").strip()

c = json.load(open(CRED))
m = imaplib.IMAP4_SSL("imap.gmail.com")
m.login(c["email"], c["app_password"])
del c
typ, _ = m.select('"[Gmail]/All Mail"', readonly=True)
typ, data = m.uid("SEARCH", None, "ALL")
uids = data[0].split()
log("dewidebug total messages in All Mail=%d" % len(uids))

senders = {}
for i in range(0, len(uids), CHUNK):
    batch = uids[i:i + CHUNK]
    rng = b",".join(batch).decode() if len(batch) < 50 else "%s:%s" % (batch[0].decode(), batch[-1].decode())
    typ, resp = m.uid("FETCH", rng, "(BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])")
    for part in resp:
        if not isinstance(part, tuple):
            continue
        uid = re.search(rb"UID (\d+)", part[0])
        msg = email.message_from_bytes(part[1])
        name, addr = parseaddr(dec(msg.get("From")))
        addr = addr.lower()
        dom = addr.split("@")[-1] if "@" in addr else "?"
        try:
            d = parsedate_to_datetime(msg.get("Date")).strftime("%Y-%m-%d")
        except Exception:
            d = ""
        s = senders.setdefault(addr, {"addr": addr, "domain": dom, "names": {}, "n": 0, "first": d, "last": d, "subjects": []})
        s["n"] += 1
        s["names"][name] = s["names"].get(name, 0) + 1
        if d and (not s["first"] or d < s["first"]): s["first"] = d
        if d and d >= s["last"]:
            s["last"] = d
        s["subjects"].append((d, dec(msg.get("Subject"))[:140], uid.group(1).decode() if uid else ""))
    log("dewidebug fetched %d/%d senders=%d" % (min(i + CHUNK, len(uids)), len(uids), len(senders)))

for s in senders.values():
    s["subjects"].sort(reverse=True)
    s["recent"] = s["subjects"][:8]
    s["distinct_subjects"] = len(set(x[1] for x in s["subjects"]))
    del s["subjects"]
    s["name"] = max(s["names"], key=s["names"].get)
    del s["names"]
m.logout()
json.dump(sorted(senders.values(), key=lambda s: -s["n"]), sys.stdout)
log("dewidebug done senders=%d" % len(senders))
