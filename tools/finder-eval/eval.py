#!/usr/bin/env python3
"""Scores the ✨ Ask service against cases.json: is each answer right, true and findable?

Usage: eval.py --serve http://localhost:8770 --label <name> [--only id,id] [--repeat N]
Needs `totum serve` running with a signed-in claude. Checks, per suggestion: the year against
Wikidata, whether the app's own sources can find it (Prowlarr on the Pi for films and series,
iTunes for podcasts, archive.org for books, a YouTube search for videos), and per ask: the kinds,
whether an expected title came back, what it avoided, time and cost. Then the copy vetting
against names whose answer is known. Writes out/<label>.json and out/<label>.md.
"""
import argparse, concurrent.futures as cf, json, re, subprocess, sys, time, urllib.parse, urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
UA = {"User-Agent": "totum-finder-eval/1 (personal test)"}


def http_json(url, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={**UA, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def run_job(serve, path, body, give_up=200):
    started = time.time()
    job = http_json(f"{serve}/{path}", body)
    while job["state"] == "running" and time.time() - started < give_up:
        time.sleep(1.5)
        job = http_json(f"{serve}/job/{job['id']}")
    return job, time.time() - started


def norm(s):
    return re.sub(r"[^a-z0-9 ]", "", s.lower().replace("&", "and")).strip()


def matches(title, wanted):
    t = norm(title)
    return any(norm(w) and (norm(w) in t or t in norm(w)) for w in wanted)


_years = {}


def wikidata_years(title):
    if title in _years:
        return _years[title]
    esc = title.replace('"', '\\"')
    q = ('SELECT DISTINCT ?date WHERE { VALUES ?label { "%s"@en "%s"@en-gb } ?item rdfs:label ?label . '
         'VALUES ?k { wd:Q11424 wd:Q5398426 wd:Q1259759 } ?item wdt:P31/wdt:P279* ?k . '
         '{ ?item wdt:P577 ?date } UNION { ?item wdt:P580 ?date } } LIMIT 30') % (esc, esc)
    try:
        d = http_json("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q), timeout=40)
        ys = sorted({int(b["date"]["value"][:4]) for b in d["results"]["bindings"] if b["date"]["value"][:4].isdigit()})
    except Exception as e:  # noqa: BLE001
        print(f"  wikidata failed for {title}: {e}", file=sys.stderr)
        ys = None
    _years[title] = ys
    return ys


def prowlarr(query, category=None):
    path = "/api/v1/search?type=search&limit=100&query=" + urllib.parse.quote(query)
    if category:
        path += f"&categories={category}"
    remote = (
        "KEY=$(sudo grep -oP '(?<=<ApiKey>)[^<]+' /var/lib/prowlarr/config/config.xml); "
        "printf '%s' \"$KEY\" | docker exec -i nginx_dewi sh -c 'read K; curl -s -m 150 -H \"X-Api-Key: $K\" "
        f"\"http://prowlarr:9696{path}\"'"
    )
    out = subprocess.run(["ssh", "-o", "BatchMode=yes", "pi@192.168.0.19", remote],
                         capture_output=True, text=True, timeout=200, stdin=subprocess.DEVNULL).stdout
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


TEN_BIT = re.compile(r"(x265|hevc|h\.?265).*(10.?bit|hi10|main10)|(10.?bit|hi10|main10).*(x265|hevc|h\.?265)", re.I)


def findable(s):
    kind, words = s["kind"], s["searchWords"]
    try:
        if kind in ("film", "series"):
            results = prowlarr(words)
            if results is None:
                return {"found": None, "detail": "prowlarr unreadable"}
            live = [r for r in results if (r.get("seeders") or 0) > 0]
            best = max((r.get("seeders") or 0 for r in live), default=0)
            ten = sum(1 for r in live if TEN_BIT.search(r.get("title", "")))
            return {"found": len(live), "detail": f"{len(live)} seeded, best {best}, {ten} 10-bit x265"}
        if kind == "podcast":
            d = http_json("https://itunes.apple.com/search?media=podcast&limit=5&term=" + urllib.parse.quote(words))
            names = [r.get("collectionName", "") for r in d.get("results", [])]
            return {"found": len(names), "detail": "; ".join(names[:3])}
        if kind == "audiobook":
            q = urllib.parse.quote(f"({words}) AND mediatype:audio")
            d = http_json(f"https://archive.org/advancedsearch.php?q={q}&fl[]=title&rows=5&output=json")
            names = [x.get("title", "") for x in d["response"]["docs"]]
            torrents = prowlarr(words, category=3030) or []
            live = [r for r in torrents if (r.get("seeders") or 0) > 0]
            return {"found": len(names) + len(live), "detail": f"archive {len(names)}: {'; '.join(names[:2])}; torrents {len(live)}"}
        if kind == "youtube":
            out = subprocess.run(["yt-dlp", "--flat-playlist", "-J", f"ytsearch5:{words}"],
                                 capture_output=True, text=True, timeout=90, stdin=subprocess.DEVNULL).stdout
            entries = (json.loads(out).get("entries") or []) if out.strip() else []
            return {"found": len(entries), "detail": "; ".join((e.get("title") or "")[:50] for e in entries[:3])}
    except Exception as e:  # noqa: BLE001
        return {"found": None, "detail": f"check failed: {e}"}
    return {"found": None, "detail": "unknown kind"}


def score_ask(case, job, took):
    row = {"id": case["id"], "took_s": round(took, 1), "state": job["state"], "error": job.get("error")}
    sugg = (job.get("ask") or {}).get("suggestions") or []
    row["suggestions"] = []
    for s in sugg:
        r = dict(s)
        if s["kind"] in ("film", "series") and s.get("year"):
            ys = wikidata_years(s["title"])
            r["year_check"] = "unknown" if not ys else ("ok" if s["year"] in ys else f"WRONG (wikidata {ys})")
        else:
            r["year_check"] = "n/a"
        r["find"] = findable(s)
        r["kind_ok"] = s["kind"] in case["kinds"]
        r["expected"] = matches(s["title"], case["expect"])
        r["avoided_ok"] = not (matches(s["title"], case.get("avoid", [])) and not s.get("alreadyHave"))
        if case.get("minYear") and s.get("year"):
            r["new_enough"] = s["year"] >= case["minYear"]
        row["suggestions"].append(r)
    n = len(sugg)
    row["count_ok"] = 3 <= n <= 5
    row["any_expected"] = any(r["expected"] for r in row["suggestions"])
    row["kinds_ok"] = n > 0 and all(r["kind_ok"] for r in row["suggestions"])
    row["years_wrong"] = sum(1 for r in row["suggestions"] if r["year_check"].startswith("WRONG"))
    row["unfindable"] = sum(1 for r in row["suggestions"] if r["find"]["found"] == 0)
    row["avoid_broken"] = sum(1 for r in row["suggestions"] if not r["avoided_ok"])
    row["too_old"] = sum(1 for r in row["suggestions"] if r.get("new_enough") is False)
    return row


def run_asks(serve, cases, parallel):
    def one(case):
        turns, have = case["turns"], case.get("have", [])
        conv = []
        job = None
        took = 0.0
        for i, t in enumerate(turns):
            conv.append({"request": t, "suggested": []})
            job, took = run_job(serve, "ask", {"turns": conv, "have": have})
            if i < len(turns) - 1:
                conv[-1]["suggested"] = [f"{s['title']} ({s.get('year')})" for s in (job.get("ask") or {}).get("suggestions", [])]
        print(f"  {case['id']}: {job['state']} in {took:.1f}s", file=sys.stderr)
        return score_ask(case, job, took)
    with cf.ThreadPoolExecutor(parallel) as pool:
        return list(pool.map(one, cases))


def run_vets(serve, vets):
    items = [{"suggestion": v["suggestion"], "copies": [{k: c[k] for k in ("name", "detail") if k in c} for c in v["copies"]]} for v in vets]
    job, took = run_job(serve, "vet", {"items": items})
    verdicts = {(x["item"], x["copy"]): x for x in (job.get("vet") or {}).get("verdicts", [])}
    rows = []
    for i, v in enumerate(vets):
        for c, copy in enumerate(v["copies"]):
            got = verdicts.get((i, c))
            rows.append({"item": v["suggestion"]["title"], "name": copy["name"], "want": copy["ok"],
                         "got": None if got is None else got["ok"], "reason": None if got is None else got["reason"]})
    return {"took_s": round(took, 1), "state": job["state"], "error": job.get("error"), "rows": rows}


def report(label, asks, vets, log_lines):
    lines = [f"# Finder eval: {label}", ""]
    n = len(asks)
    sugg = [s for a in asks for s in a["suggestions"]]
    times = sorted(a["took_s"] for a in asks)
    lines += [
        "## Summary", "",
        f"- asks answered: {sum(a['state'] == 'done' for a in asks)}/{n}",
        f"- 3–5 suggestions: {sum(a['count_ok'] for a in asks)}/{n}",
        f"- an expected title among them: {sum(a['any_expected'] for a in asks)}/{n}",
        f"- kinds all right: {sum(a['kinds_ok'] for a in asks)}/{n}",
        f"- wrong years: {sum(a['years_wrong'] for a in asks)} of {sum(1 for s in sugg if s['year_check'] != 'n/a')} checked "
        f"({sum(1 for s in sugg if s['year_check'] == 'unknown')} unknown to Wikidata)",
        f"- suggestions the app could not find: {sum(a['unfindable'] for a in asks)} of {len(sugg)}",
        f"- already-had items suggested anyway: {sum(a['avoid_broken'] for a in asks)}",
        f"- older than asked: {sum(a['too_old'] for a in asks)}",
        f"- time: median {times[len(times)//2] if times else 0}s, max {times[-1] if times else 0}s",
    ]
    if vets:
        judged = [r for r in vets["rows"] if r["got"] is not None]
        right = sum(r["got"] == r["want"] for r in judged)
        lines += [f"- vetting: {right}/{len(vets['rows'])} right ({len(vets['rows']) - len(judged)} without a verdict), {vets['took_s']}s"]
    lines += ["", "## Asks", ""]
    for a in asks:
        lines.append(f"### {a['id']} — {a['state']} in {a['took_s']}s" + (f" ❌ {a['error']}" if a.get("error") else ""))
        for s in a["suggestions"]:
            flags = []
            if not s["kind_ok"]:
                flags.append("wrong kind")
            if s["year_check"].startswith("WRONG"):
                flags.append(s["year_check"])
            if s["find"]["found"] == 0:
                flags.append("NOT FOUND")
            if not s["avoided_ok"]:
                flags.append("already had")
            if s.get("new_enough") is False:
                flags.append("too old")
            mark = "✅" if not flags else "⚠️ " + ", ".join(flags)
            lines.append(f"- {mark} {s['kind']} **{s['title']}** ({s.get('year')}) {'· expected' if s['expected'] else ''}"
                         f" — {s['why']} · search \"{s['searchWords']}\" → {s['find']['detail']}")
        lines.append("")
    if vets:
        lines += ["## Vetting", "", "| item | copy | want | got | reason |", "|---|---|---|---|---|"]
        for r in vets["rows"]:
            ok = "✅" if r["got"] == r["want"] else "❌"
            lines.append(f"| {r['item']} | {r['name']} | {r['want']} | {ok} {r['got']} | {r['reason']} |")
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--serve", default="http://localhost:8770")
    p.add_argument("--label", required=True)
    p.add_argument("--only")
    p.add_argument("--parallel", type=int, default=2)
    p.add_argument("--no-vet", action="store_true")
    a = p.parse_args()
    cases = json.loads((HERE / "cases.json").read_text())
    asks = cases["asks"] if not a.only else [c for c in cases["asks"] if c["id"] in a.only.split(",")]
    print(f"{len(asks)} asks against {a.serve}", file=sys.stderr)
    scored = run_asks(a.serve, asks, a.parallel)
    vets = None if a.no_vet else run_vets(a.serve, cases["vets"])
    out = HERE / "out"
    out.mkdir(exist_ok=True)
    (out / f"{a.label}.json").write_text(json.dumps({"asks": scored, "vets": vets}, indent=1))
    text = report(a.label, scored, vets, [])
    (out / f"{a.label}.md").write_text(text)
    print(text.split("## Asks")[0])


if __name__ == "__main__":
    main()
