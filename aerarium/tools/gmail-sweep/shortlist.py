import json, re, sys
senders = json.load(open(sys.argv[1]))
KW = re.compile(r"\b(bank|banking|statement|isa|invest|investment|pension|sipp|card|credit|loan|mortgage|direct debit|bill|billing|payment|paid|account|energy|gas|electric|electricity|water|broadband|fibre|mobile|insurance|insure|policy|premium|council tax|tax|hmrc|bonds|savings|saver|interest|dividend|trading|shares|stocks|portfolio|crypto|balance|overdraft|transaction|subscription|renewal|invoice|receipt|payslip|salary|pay ?slip|tv licen[cs]e|rent|lease|finance|financial|wealth|fund|annuity|bnpl|clearpay|paypal|direct debit|standing order|cashback|reward|tariff|meter|contract|annual summary|tax year|p60|p45)\b", re.I)
rows = []
for s in senders:
    blob = " ".join([s["addr"], s["name"]] + [x[1] for x in s["recent"]])
    hits = set(h.lower() for h in KW.findall(blob))
    if hits:
        rows.append((s["n"], s["domain"], s["addr"], s["name"][:30], s["first"], s["last"], ",".join(sorted(hits))[:60], s["recent"][0][1][:70]))
print("matched senders:", len(rows), "of", len(senders))
for r in rows:
    print(" | ".join(str(x) for x in r))
