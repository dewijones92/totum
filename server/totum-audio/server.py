#!/usr/bin/env python3
"""Audio-only HLS for TorrServer streams — the data-saving half of Listen mode.

A torrent is one file carrying both tracks, so a phone listening to one still pulls the video.
Measured on this Pi: 15.2 MB/min for the whole stream against 2.1 MB/min for the audio alone.
This remuxes the audio out (a stream COPY, never a transcode) and serves it as HLS, which
ExoPlayer can seek natively — a piped remux cannot, and that was the only real objection.

One ffmpeg per hash:index, started on demand and reaped when nobody is listening. The reaping
is the part that matters: an orphaned ffmpeg per abandoned tap would be a slow leak on a Pi
that is 88% full.
"""
import os
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TORRSERVER = os.environ.get("TORRSERVER", "http://localhost:8090")
WORK = os.environ.get("WORK_DIR", "/tmp/totum-audio")
PORT = int(os.environ.get("PORT", "8099"))

# Long enough to survive a pause or a walk between rooms; short enough that an abandoned tap
# costs a couple of minutes of one ffmpeg rather than the uptime of the Pi.
IDLE_SECONDS = int(os.environ.get("IDLE_SECONDS", "180"))
# The first segment took ~25s cold in testing, so a play that arrives before the warm-up waits
# rather than 404s. Beyond this something is genuinely wrong and saying so beats hanging.
FIRST_SEGMENT_TIMEOUT = int(os.environ.get("FIRST_SEGMENT_TIMEOUT", "60"))
SEGMENT_SECONDS = "6"

_jobs = {}
_lock = threading.Lock()


class Job:
    def __init__(self, key, torrent_hash, index):
        self.key = key
        self.dir = os.path.join(WORK, key)
        self.touched = time.time()
        os.makedirs(self.dir, exist_ok=True)
        source = f"{TORRSERVER}/stream?link={torrent_hash}&index={index}&play"
        self.proc = subprocess.Popen(
            [
                "ffmpeg", "-nostdin", "-loglevel", "error",
                "-i", source,
                # ONE audio track, the first. A release often carries several — commentary, a
                # second language — and `-vn` alone copies them all, which doubled the data on
                # the very first test. Copy, not encode: the Pi's CPU is never the constraint.
                "-map", "0:a:0", "-c:a", "copy",
                "-f", "hls",
                "-hls_time", SEGMENT_SECONDS,
                # Keep every segment listed, so seeking backwards works for the whole episode.
                "-hls_list_size", "0",
                os.path.join(self.dir, "index.m3u8"),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    @property
    def alive(self):
        return self.proc.poll() is None

    def stop(self):
        if self.alive:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        shutil.rmtree(self.dir, ignore_errors=True)


def ensure(torrent_hash, index):
    key = f"{torrent_hash}-{index}"
    with _lock:
        job = _jobs.get(key)
        # A dead ffmpeg is replaced rather than reused: the torrent may simply not have been
        # ready the first time, and a stale corpse would serve a truncated playlist forever.
        if job is not None and not job.alive:
            job.stop()
            job = None
        if job is None:
            job = Job(key, torrent_hash, index)
            _jobs[key] = job
        job.touched = time.time()
        return job


def reap():
    while True:
        time.sleep(30)
        now = time.time()
        with _lock:
            for key, job in list(_jobs.items()):
                if now - job.touched > IDLE_SECONDS:
                    job.stop()
                    del _jobs[key]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # nginx already logs; a second copy on a full Pi helps nobody.

    def do_GET(self):
        path, _, query = self.path.partition("?")
        parts = [p for p in path.split("/") if p]
        # /audio/<hash>/<index>/<file>   or   /audio/<hash>/<index>/start
        if len(parts) != 4 or parts[0] != "audio":
            return self._send(404, b"not found")
        _, torrent_hash, index, name = parts
        if not torrent_hash.isalnum() or not index.isdigit():
            return self._send(400, b"bad request")

        job = ensure(torrent_hash, index)

        # Warm-up call, made when a search result is opened: start the work and return at once,
        # so the ~25s to the first segment is spent while the file list is still on screen.
        if name == "start":
            return self._send(202, b"starting")

        # Only ever the playlist or a segment, and never a path from the request: anything else
        # would make this a file server for the whole Pi.
        if name == "index.m3u8":
            path = os.path.join(job.dir, "index.m3u8")
            kind = "application/vnd.apple.mpegurl"
        elif name.startswith("index") and name.endswith(".ts"):
            path = os.path.join(job.dir, os.path.basename(name))
            kind = "video/mp2t"
        else:
            return self._send(404, b"not found")

        deadline = time.time() + (FIRST_SEGMENT_TIMEOUT if name == "index.m3u8" else 30)
        while not os.path.exists(path) and time.time() < deadline:
            if not job.alive and not os.path.exists(path):
                return self._send(502, b"remux failed")
            time.sleep(0.5)
        if not os.path.exists(path):
            return self._send(504, b"not ready")

        with open(path, "rb") as f:
            body = f.read()

        # The playlist's segment lines are RELATIVE, and a relative URL does NOT inherit the
        # query string it was fetched with — so a player resolving "index0.ts" against
        # ".../index.m3u8?totumToken=X" asks for it with no token at all and the gate answers
        # 401. Measured, after an earlier comment here confidently claimed the opposite.
        # So the token this request arrived with is stamped onto every segment.
        if name == "index.m3u8" and query:
            body = b"\n".join(
                line + b"?" + query.encode() if line.endswith(b".ts") else line
                for line in body.split(b"\n")
            )

        self._send(200, body, kind)

    def _send(self, code, body, kind="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        # A growing playlist must never be cached, or seeking lands in a stale one.
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    os.makedirs(WORK, exist_ok=True)
    threading.Thread(target=reap, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
