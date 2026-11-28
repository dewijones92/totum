#!/usr/bin/env python3
"""Stands in for the phone's PC-screen page server (ScreenLink) so the REAL bundled screen.html can be driven by a
browser against the REAL home-server gate, with no phone. ADR 182 (amended): a PC screen takes the public cast link.

  phone-screen-standin.py <screen.html> <port> <stream-url> [direct|phone]

It serves the page at /screen, answers hello/poll/state the way ScreenLink does, and, once the page has said hello,
sends ONE load command for <stream-url>. "direct" marks the command as the phone now does for a public link ("direct":
true); "phone" sends it the old way, which the page rewrites onto its own origin (a 404 here: that is the red case).
Everything the page asks for is printed, with keys and links shortened.
"""
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

page_path, port, stream_url = sys.argv[1], int(sys.argv[2]), sys.argv[3]
mode = sys.argv[4] if len(sys.argv) > 4 else "direct"
page = open(page_path, "rb").read()
lock = threading.Lock()
commands = []
state = {"hello": False, "reports": []}


def shown(text):
    return re.sub(r"(cast-out/[0-9a-f]{6})[0-9a-f]{58}", r"\1...", text)


def say(text):
    print(time.strftime("%H:%M:%S"), shown(text), flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body, kind="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/screen":
            return self.reply(200, page, "text/html; charset=utf-8")
        if url.path == "/screen/hello":
            with lock:
                first = not state["hello"]
                state["hello"] = True
                if first:
                    command = {"do": "load", "load": 1, "url": stream_url, "pos": 0, "play": True, "rate": 1.0,
                               "title": "A home film", "subs": None}
                    if mode == "direct":
                        command["direct"] = True
                    commands.append({"n": 1, "command": command})
                    say(f"hello from {query.get('name')} codecs={query.get('codecs')}; queued a {mode} load of {stream_url}")
            return self.reply(200, {"id": "pc-test", "after": 0, "host": "phone", "now": None})
        if url.path == "/screen/poll":
            after = int(query.get("after", "0"))
            for _ in range(20):
                with lock:
                    due = [c for c in commands if c["n"] > after]
                if due:
                    return self.reply(200, due)
                time.sleep(0.1)
            return self.reply(200, [])
        if url.path == "/screen/state":
            with lock:
                state["reports"].append({k: query.get(k) for k in ("pos", "playing", "buffering", "ended", "error")})
            if query.get("error"):
                say(f"page reports error: {query['error']}")
            return self.reply(200, {"now": None})
        say(f"404 {self.path.split('?')[0]} (a path the stand-in does not serve)")
        return self.reply(404, {"error": "not here"})


say(f"phone stand-in on :{port}, mode={mode}")
ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
