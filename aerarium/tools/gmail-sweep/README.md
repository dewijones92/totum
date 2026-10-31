# Gmail sweep (one-off inventory)

The scripts that built the 2026-10-29 inventory of every bank, ISA, pension and bill from Gmail. They run **on the
Pi**, read the Gmail app password from the Hermes bridge's credential file at run time
(`/var/lib/hermes-gmail/gmail.json`, root-only, so `sudo`), open `[Gmail]/All Mail` read-only and fetch with
`BODY.PEEK` (nothing is marked read). Output goes to stdout; keep it outside this repo
(`~/.local/share/finance-hub/` on the laptop), because it is personal data.

| Script             | What                                                                                                      |
| ------------------ | --------------------------------------------------------------------------------------------------------- |
| `sweep_headers.py` | From/Subject/Date of every message, aggregated per sender (JSON)                                          |
| `shortlist.py`     | Filters that JSON to senders that look financial                                                          |
| `fetch_bodies.py`  | For a JSON list of `{label, q}` Gmail queries on stdin, the newest N bodies and their money-looking lines |
| `auth_headers.py`  | SPF/DKIM/DMARC results for matching messages, to tell a genuine bank email from phishing                  |

```bash
scp tools/gmail-sweep/*.py "pi@$PI_HOST:finance-sweep/"
ssh "pi@$PI_HOST" 'sudo python3 -u ~/finance-sweep/sweep_headers.py' > ~/.local/share/finance-hub/senders.json
```

Gmail search goes inside an IMAP quoted string, so a query must not contain double quotes.
