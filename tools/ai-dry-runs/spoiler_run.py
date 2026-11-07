#!/usr/bin/env python3
"""Run the spoiler shield's dry run: titles in, safe titles out, one table.

    tools/ai-dry-runs/spoiler_run.py titles.txt [--port 8770]

`titles.txt` has one `id|channel|title` per line (public video titles). Titles go to the server in batches of 25,
as the phone sends them, and every answer is printed beside its original so each can be judged by eye.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run import call, run  # noqa: E402

BATCH = 25


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("titles")
    parser.add_argument("--port", type=int, default=8770)
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    print("server:", call(base, "GET", "/health"), file=sys.stderr)
    rows = [line.rstrip("\n").split("|", 2) for line in open(args.titles) if line.strip()]
    items = [{"id": r[0], "channel": r[1], "title": r[2]} for r in rows]
    for start in range(0, len(items), BATCH):
        batch = items[start:start + BATCH]
        job, took = run(base, "spoiler", {"items": batch})
        print(f"--- batch {start // BATCH + 1}: {len(batch)} titles, {job['state']} in {took:.1f}s", file=sys.stderr)
        if job["state"] != "done":
            print("FAILED:", job.get("error"))
            continue
        said = {v["id"]: v for v in job["result"]["verdicts"]}
        for item in batch:
            v = said.get(item["id"])
            if v is None:
                print(f"[{item['channel']}] {item['title']}\n    -> NO VERDICT (stays hidden)")
            else:
                mark = "SPOILS" if v["spoils"] else "ok    "
                print(f"[{item['channel']}] {item['title']}\n    -> {mark} {v['safeTitle']}")


if __name__ == "__main__":
    main()
