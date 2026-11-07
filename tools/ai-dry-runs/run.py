#!/usr/bin/env python3
"""Post one request to a local `totum serve` AI task and print the answer.

    tools/ai-dry-runs/run.py <task> <request.json> [--port 8770] [--out answer.json]

`task` is one of spoiler, transcript-digest, transcript-ask, for-you, morning-digest. The server is the CLI's
`totum serve` (build it with `./gradlew :cli:installDist`, start `cli/build/install/totum/bin/totum serve
--port=N` with TOTUM_DATA and TOTUM_DOWNLOADS pointing at a scratch directory). Prints the elapsed time first so a
dry run records how long Haiku took, and checks the port is answering before posting.
"""
import argparse
import json
import sys
import time
import urllib.request


def call(base, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())


def run(base, task, request, give_up_s=330):
    started = time.time()
    job = call(base, "POST", f"/ai/{task}", request)
    while job["state"] == "running":
        if time.time() - started > give_up_s:
            raise SystemExit(f"gave up after {give_up_s}s")
        time.sleep(1.5)
        job = call(base, "GET", f"/ai/job/{job['id']}")
    return job, time.time() - started


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("task")
    parser.add_argument("request")
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--out")
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    health = call(base, "GET", "/health")
    print(f"server on {args.port}: {health}", file=sys.stderr)
    request = json.load(open(args.request))
    job, took = run(base, args.task, request)
    print(f"{args.task}: {job['state']} in {took:.1f}s", file=sys.stderr)
    if job["state"] != "done":
        raise SystemExit(f"failed: {job.get('error')}")
    text = json.dumps(job["result"], ensure_ascii=False, indent=2)
    if args.out:
        open(args.out, "w").write(text)
    print(text)


if __name__ == "__main__":
    main()
