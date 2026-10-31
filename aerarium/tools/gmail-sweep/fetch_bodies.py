import imaplib, json, sys, time, email, re, html
from email.header import decode_header, make_header
from email.utils import parsedate_to_datetime

CRED = "/var/lib/hermes-gmail/gmail.json"
PER_SENDER = int(sys.argv[1]) if len(sys.argv) > 1 else 3
MAX_CHARS = 6000
SAL = re.compile(r"£|\b(balance|account|ISA|rate|AER|interest|due|policy|premium|renew|tariff|direct debit|monthly|annual|statement|pension|fund|value|plan|contract|bill|limit|minimum|mortgage|term|matur|ending|sort code|holding|portfolio|transfer)\b", re.I)

def log(msg):
    sys.stderr.write(time.strftime("%H:%M:%S ") + msg + "\n"); sys.stderr.flush()

def dec(v):
    try:
        return str(make_header(decode_header(v or ""))).strip()
    except Exception:
        return (v or "").strip()

def text_of(msg):
    plain, htm = None, None
    for part in msg.walk():
        ct = part.get_content_type()
        if part.get_content_maintype() == "multipart" or part.get_filename():
            continue
        try:
            payload = part.get_payload(decode=True)
            if payload is None:
                continue
            s = payload.decode(part.get_content_charset() or "utf-8", "replace")
        except Exception:
            continue
        if ct == "text/plain" and plain is None:
            plain = s
        elif ct == "text/html" and htm is None:
            htm = s
    if plain and len(plain.strip()) > 40:
        out = plain
    elif htm:
        out = re.sub(r"(?is)<(script|style|head).*?</\1>", " ", htm)
        out = re.sub(r"(?s)<[^>]+>", " ", out)
        out = html.unescape(out)
    else:
        out = plain or ""
    out = re.sub(r"[ \t ‌​]+", " ", out)
    out = re.sub(r"\s*\n\s*", "\n", out).strip()
    return out[:MAX_CHARS]

targets = json.load(sys.stdin)
c = json.load(open(CRED))
m = imaplib.IMAP4_SSL("imap.gmail.com")
m.login(c["email"], c["app_password"])
del c
m.select('"[Gmail]/All Mail"', readonly=True)
out = {}
for i, t in enumerate(targets):
    typ, data = m.uid("SEARCH", None, "X-GM-RAW", '"%s"' % t["q"])
    uids = data[0].split()[-PER_SENDER:][::-1]
    msgs = []
    for u in uids:
        typ, resp = m.uid("FETCH", u, "(BODY.PEEK[])")
        raw = next((p[1] for p in resp if isinstance(p, tuple)), None)
        if raw is None:
            continue
        msg = email.message_from_bytes(raw)
        try:
            d = parsedate_to_datetime(msg.get("Date")).strftime("%Y-%m-%d")
        except Exception:
            d = ""
        atts = [dec(p.get_filename()) for p in msg.walk() if p.get_filename()]
        body = text_of(msg)
        sal = [l for l in body.split("\n") if SAL.search(l)][:25]
        msgs.append({"salient": sal, "date": d, "from": dec(msg.get("From")), "subject": dec(msg.get("Subject")), "attachments": atts, "text": body})
    out[t["label"] + " :: " + t["q"]] = {"total_matching": len(data[0].split()), "messages": msgs}
    log("dewidebug %d/%d %s matched=%d fetched=%d" % (i + 1, len(targets), t["q"], len(data[0].split()), len(msgs)))
m.logout()
json.dump(out, sys.stdout)
