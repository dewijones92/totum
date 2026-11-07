#!/usr/bin/env python3
"""Run the transcript dry runs: a caption file in, a TL;DR with chapters (or an answer to a question) out.

    tools/ai-dry-runs/transcript_run.py captions.vtt "Title" rolling(true|false) [--ask "question"] [--port 8770]

Builds the request with the CLI's own TranscriptText (the same blocks the phone sends), posts it to a local
`totum serve`, prints the answer and checks every chapter and every quoted moment against the cues, so a wrong
second or an invented quote shows up as a line starting with MISMATCH.
"""
import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run import call, run  # noqa: E402

LIB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "cli", "build", "install", "totum", "lib")
MAIN = "com.dewijones92.totum.cli.serve.tasks.TranscriptRequestToolKt"


def request_for(vtt, title, rolling, question):
    args = ["java", "-cp", os.path.join(LIB, "*"), MAIN, vtt, title, rolling] + ([question] if question else [])
    done = subprocess.run(args, capture_output=True, text=True, check=True)
    print(done.stderr.strip(), file=sys.stderr)
    return json.loads(done.stdout)


def stamp(sec):
    h, m, s = sec // 3600, sec % 3600 // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def blocks_of(transcript):
    found = []
    for line in transcript.splitlines():
        match = re.match(r"\[([\d:]+)] (.*)", line)
        if match:
            parts = [int(p) for p in match.group(1).split(":")]
            found.append((sum(p * 60 ** i for i, p in enumerate(reversed(parts))), match.group(2)))
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("vtt")
    parser.add_argument("title")
    parser.add_argument("rolling")
    parser.add_argument("--ask")
    parser.add_argument("--port", type=int, default=8770)
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    print("server:", call(base, "GET", "/health"), file=sys.stderr)
    request = request_for(args.vtt, args.title, args.rolling, args.ask)
    blocks = blocks_of(request["transcript"])
    task = "transcript-ask" if args.ask else "transcript-digest"
    job, took = run(base, task, request)
    print(f"{task}: {job['state']} in {took:.1f}s", file=sys.stderr)
    if job["state"] != "done":
        raise SystemExit(f"failed: {job.get('error')}")
    result = job["result"]
    if args.ask:
        print(f"Q: {args.ask}\nfound={result['found']}\nA: {result['answer']}")
        for moment in result["moments"]:
            at = moment["atSec"]
            block = next((t for s, t in blocks if s == at), None)
            ok = block is not None and all(w in block.lower() for w in moment["quote"].lower().split()[:2])
            print(f"  {stamp(at)} \"{moment['quote']}\"{'' if ok else '   MISMATCH: no block starts there with those words'}")
    else:
        print("TL;DR:")
        for line in result["tldr"]:
            print("  -", line)
        print(f"chapters ({len(result['chapters'])}), video length {stamp(request['durationSec'])}:")
        for chapter in result["chapters"]:
            print(f"  {stamp(chapter['startSec'])}  {chapter['title']}")


if __name__ == "__main__":
    main()
