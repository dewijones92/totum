import imaplib, json, sys, email
c = json.load(open("/var/lib/hermes-gmail/gmail.json"))
m = imaplib.IMAP4_SSL("imap.gmail.com"); m.login(c["email"], c["app_password"]); del c
m.select('"[Gmail]/All Mail"', readonly=True)
for q in sys.argv[1:]:
    typ, data = m.uid("SEARCH", None, "X-GM-RAW", '"%s"' % q)
    for u in data[0].split()[-3:]:
        typ, resp = m.uid("FETCH", u, "(BODY.PEEK[HEADER.FIELDS (FROM DATE SUBJECT RETURN-PATH ARC-AUTHENTICATION-RESULTS AUTHENTICATION-RESULTS)])")
        raw = next(p[1] for p in resp if isinstance(p, tuple)).decode("utf-8", "replace")
        print("=====", q, "\n", raw[:1800])
m.logout()
