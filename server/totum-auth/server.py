"""Per-device tokens for the totum. gate.

nginx asks /check before every request to totum.<domain> (auth_request). A device gets its token by
signing in through totumauth.<domain>, which Google gates; the token is shown to this service only once,
and only its SHA-256 is stored, with the device's name and when it was last used. A device unused for
TOKEN_DAYS days is forgotten. The old shared token is accepted until /data/legacy-retired exists.

    python3 server.py                 serve
    python3 server.py devices         list devices
    python3 server.py revoke <id>     sign one device out
    python3 server.py retire-legacy   stop accepting the old shared token
"""
import hashlib
import hmac
import html
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlsplit

DATA = os.environ.get("DATA", "/data")
PORT = int(os.environ.get("PORT", "8097"))
TOKEN_DAYS = int(os.environ.get("TOKEN_DAYS", "30"))
MAX_DEVICES = 20
TOUCH_EVERY_S = 600
LOG_EVERY_S = 600
DAY_S = 86400


def log(message):
    print(time.strftime("%Y-%m-%d %H:%M:%S ") + message, flush=True)


def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def read_secret(name):
    try:
        with open(os.path.join(DATA, name)) as f:
            return f.read().strip()
    except FileNotFoundError:
        return ""


class Devices:
    def __init__(self, path, clock=time.time, days=TOKEN_DAYS):
        self.path = path
        self.clock = clock
        self.ttl = days * DAY_S
        self.lock = threading.Lock()
        self.loaded = None
        self.devices = []
        self._refresh()

    def _mtime(self):
        try:
            return os.stat(self.path).st_mtime_ns
        except FileNotFoundError:
            return None

    def _refresh(self):
        stamp = self._mtime()
        if stamp == self.loaded and self.loaded is not None:
            return
        try:
            with open(self.path) as f:
                self.devices = json.load(f)
        except FileNotFoundError:
            self.devices = []
        self.loaded = stamp

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.devices, f, indent=1)
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.path)
        self.loaded = self._mtime()

    def _expire(self):
        now = self.clock()
        gone = [d for d in self.devices if now - d["lastUsed"] > self.ttl]
        for d in gone:
            log(f"device {d['id']} \"{d['name']}\" forgotten: unused for {int((now - d['lastUsed']) / DAY_S)} days")
        if gone:
            self.devices = [d for d in self.devices if d not in gone]
            self._save()

    def mint(self, name):
        name = (name or "a device").strip()[:60] or "a device"
        token = secrets.token_hex(32)
        now = self.clock()
        with self.lock:
            self._refresh()
            self._expire()
            replaced = [d for d in self.devices if d["name"].lower() == name.lower()]
            self.devices = [d for d in self.devices if d not in replaced]
            self.devices.sort(key=lambda d: d["lastUsed"])
            while len(self.devices) >= MAX_DEVICES:
                dropped = self.devices.pop(0)
                log(f"device {dropped['id']} \"{dropped['name']}\" dropped: more than {MAX_DEVICES} devices")
            device = {"id": secrets.token_hex(4), "name": name, "hash": digest(token), "created": now, "lastUsed": now}
            self.devices.append(device)
            self._save()
        log(f"device {device['id']} \"{name}\" signed in" + (f", replacing {replaced[0]['id']}" if replaced else ""))
        return token, device

    def find(self, token):
        if not token:
            return None
        wanted = digest(token)
        now = self.clock()
        with self.lock:
            self._refresh()
            for d in self.devices:
                if hmac.compare_digest(d["hash"], wanted):
                    if now - d["lastUsed"] > self.ttl:
                        return None
                    if now - d["lastUsed"] > TOUCH_EVERY_S:
                        d["lastUsed"] = now
                        self._save()
                    return d
        return None

    def revoke(self, device_id):
        with self.lock:
            self._refresh()
            before = len(self.devices)
            self.devices = [d for d in self.devices if d["id"] != device_id]
            if len(self.devices) == before:
                return False
            self._save()
        log(f"device {device_id} signed out")
        return True

    def listing(self, current_id=None):
        with self.lock:
            self._refresh()
            self._expire()
            return [
                {
                    "id": d["id"],
                    "name": d["name"],
                    "created": int(d["created"]),
                    "lastUsed": int(d["lastUsed"]),
                    "expires": int(d["lastUsed"] + self.ttl),
                    "current": d["id"] == current_id,
                }
                for d in sorted(self.devices, key=lambda d: -d["lastUsed"])
            ]


class Gate:
    """The decision /check makes, separate from HTTP so it can be tested without a socket."""

    def __init__(self, devices, legacy_token, legacy_retired, clock=time.time):
        self.devices = devices
        self.legacy_token = legacy_token
        self.legacy_retired = legacy_retired
        self.clock = clock
        self.counts = {"device": 0, "legacy": 0, "refused": 0}
        self.logged_at = clock()

    def check(self, header_token, original_uri):
        device = self.devices.find(header_token)
        if device:
            return self._count("device", device)
        if self.legacy_token and not self.legacy_retired():
            query = parse_qs(urlsplit(original_uri or "").query).get("totumToken", [""])[0]
            for offered in (header_token, query):
                if offered and hmac.compare_digest(offered, self.legacy_token):
                    return self._count("legacy", None)
        return self._count("refused", None)

    def _count(self, outcome, device):
        self.counts[outcome] += 1
        now = self.clock()
        if now - self.logged_at >= LOG_EVERY_S:
            log("checks in the last %ds: %s" % (now - self.logged_at, ", ".join(f"{k}={v}" for k, v in self.counts.items())))
            self.counts = {k: 0 for k in self.counts}
            self.logged_at = now
        return outcome, device


def signin_page(token, prowlarr_key, device):
    link = "totum://auth?token=%s&key=%s&device=%s" % (token, quote(prowlarr_key), quote(device["id"]))
    safe = html.escape(link, quote=True)
    return (
        '<!doctype html><meta charset="utf-8"><title>Totum</title>'
        f'<meta http-equiv="refresh" content="0;url={safe}">'
        '<body style="font-family:system-ui;padding:2rem">'
        f'<p>Signing Totum in as &ldquo;{html.escape(device["name"])}&rdquo;&hellip;</p>'
        f'<p><a href="{safe}">Tap here if it does not open automatically.</a></p></body>'
    ).encode()


def handler_for(gate, devices, signin_secret, prowlarr_key):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _send(self, code, body=b"", kind="text/plain", headers=()):
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in headers:
                self.send_header(k, v)
            self.end_headers()
            if body:
                self.wfile.write(body)

        def _device(self):
            return devices.find(self.headers.get("X-Totum-Token", ""))

        def do_GET(self):
            url = urlsplit(self.path)
            query = parse_qs(url.query)
            if url.path == "/health":
                return self._send(200, b"ok")
            if url.path == "/check":
                outcome, device = gate.check(self.headers.get("X-Totum-Token", ""), self.headers.get("X-Original-URI", ""))
                if outcome == "refused":
                    return self._send(401)
                return self._send(204, headers=[("X-Totum-Device", device["id"] if device else "legacy")])
            if url.path == "/signin":
                offered = self.headers.get("X-Totum-Signin-Gate", "")
                if not signin_secret or not hmac.compare_digest(offered, signin_secret):
                    log("signin refused: it did not come through the Google-gated host")
                    return self._send(403)
                token, device = devices.mint(query.get("device", [""])[0])
                return self._send(200, signin_page(token, prowlarr_key, device), "text/html; charset=utf-8")
            if url.path == "/auth/devices":
                device = self._device()
                if not device:
                    return self._send(401)
                body = json.dumps(devices.listing(device["id"])).encode()
                return self._send(200, body, "application/json")
            return self._send(404)

        def do_POST(self):
            url = urlsplit(self.path)
            device = self._device()
            if not device:
                return self._send(401)
            if url.path == "/auth/signout":
                devices.revoke(device["id"])
                return self._send(204)
            if url.path == "/auth/revoke":
                target = parse_qs(url.query).get("id", [""])[0]
                return self._send(204 if devices.revoke(target) else 404)
            return self._send(404)

    return Handler


def main(argv):
    devices = Devices(os.path.join(DATA, "devices.json"))
    retired_flag = os.path.join(DATA, "legacy-retired")
    command = argv[1] if len(argv) > 1 else "serve"
    if command == "devices":
        for d in devices.listing():
            print(f"{d['id']}  {d['name']:<30} last used {time.strftime('%Y-%m-%d %H:%M', time.localtime(d['lastUsed']))}")
        return 0
    if command == "revoke":
        return 0 if devices.revoke(argv[2]) else 1
    if command == "retire-legacy":
        open(retired_flag, "w").close()
        log("the old shared token is no longer accepted")
        return 0
    gate = Gate(devices, read_secret("token"), lambda: os.path.exists(retired_flag))
    server = ThreadingHTTPServer(("0.0.0.0", PORT), handler_for(gate, devices, read_secret("signin-gate"), read_secret("prowlarr-key")))
    log(f"totum-auth on :{PORT}: {len(devices.listing())} device(s), "
        f"old shared token {'retired' if os.path.exists(retired_flag) else 'still accepted'}")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
