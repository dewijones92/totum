#!/usr/bin/env python3
import argparse
import asyncio
import plistlib
import socket
import sys
import time
import urllib.request

from zeroconf import IPVersion
from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf


def log(message):
    sys.stdout.write("%s standin %s\n" % (time.strftime("%H:%M:%S"), message))
    sys.stdout.flush()


class Playback:
    def __init__(self):
        self.url = None
        self.position = 0.0
        self.duration = None
        self.rate = 0.0
        self.since = time.monotonic()
        self.segments = 0
        self.task = None

    def now(self):
        if self.rate:
            return min(self.position + (time.monotonic() - self.since) * self.rate, self.duration or 1e9)
        return self.position

    def set(self, position=None, rate=None):
        self.position = self.now() if position is None else position
        if rate is not None:
            self.rate = rate
        self.since = time.monotonic()


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as reply:
        return reply.read()


async def consume(state):
    url = state.url
    loop = asyncio.get_running_loop()
    playlist = (await loop.run_in_executor(None, fetch, url)).decode()
    log("fetched the playlist (%d bytes) from %s" % (len(playlist), url))
    base = url.rsplit("/", 1)[0] + "/"
    lines = playlist.splitlines()
    init = next(line.split('URI="')[1].rstrip('"') for line in lines if line.startswith("#EXT-X-MAP"))
    pieces = (await loop.run_in_executor(None, fetch, base + init))[4:8]
    log("fetched the init segment %s, box %r" % (init, pieces))
    durations = [float(line.split(":")[1].rstrip(",")) for line in lines if line.startswith("#EXTINF")]
    names = [line for line in lines if line and not line.startswith("#")]
    state.duration = sum(durations)
    state.set(rate=1.0)
    starts = [sum(durations[:i]) for i in range(len(durations))]
    fetched = set()
    while state.url == url:
        here = state.now()
        for i, name in enumerate(names):
            if i in fetched or starts[i] > here + 8:
                continue
            body = await loop.run_in_executor(None, fetch, base + name)
            fetched.add(i)
            state.segments += 1
            log("segment %d fetched (%d bytes, box %r) at position %.1fs" % (i, len(body), body[4:8], here))
        if here >= state.duration:
            log("reached the end at %.1fs" % here)
            state.duration = None
            state.rate = 0.0
            return
        await asyncio.sleep(0.25)


def build(state):
    def play(query, body):
        parsed = plistlib.loads(body)
        state.url = parsed["Content-Location"]
        state.position = float(parsed.get("Start-Position", 0.0) or 0.0)
        state.duration = None
        state.rate = 0.0
        state.segments = 0
        log("POST /play url=%s start=%.1f" % (state.url, state.position))
        if state.task:
            state.task.cancel()
        state.task = asyncio.ensure_future(consume(state))
        return 200, b"", "text/plain"

    def playback_info(query, body):
        if state.url is None or state.duration is None:
            payload = {"readyToPlay": False}
        else:
            payload = {"duration": state.duration, "position": state.now(), "rate": state.rate, "readyToPlay": True}
        return 200, plistlib.dumps(payload), "text/x-apple-plist+xml"

    def rate(query, body):
        value = float(query.get("value", "0"))
        state.set(rate=value)
        log("POST /rate value=%s (position %.1fs)" % (value, state.position))
        return 200, b"", "text/plain"

    def scrub_set(query, body):
        position = float(query["position"])
        state.set(position=position)
        log("POST /scrub position=%.1f" % position)
        return 200, b"", "text/plain"

    def scrub_get(query, body):
        return 200, ("duration: %f\nposition: %f\n" % (state.duration or 0, state.now())).encode(), "text/plain"

    def stop(query, body):
        log("POST /stop")
        state.url = None
        state.duration = None
        state.rate = 0.0
        return 200, b"", "text/plain"

    return {
        ("POST", "/play"): play,
        ("GET", "/playback-info"): playback_info,
        ("POST", "/rate"): rate,
        ("POST", "/scrub"): scrub_set,
        ("GET", "/scrub"): scrub_get,
        ("POST", "/stop"): stop,
    }


async def serve(routes, reader, writer):
    try:
        while True:
            head = await reader.readuntil(b"\r\n\r\n")
            lines = head.decode("latin-1").split("\r\n")
            method, target, _ = lines[0].split(" ", 2)
            headers = {k.strip().lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
            body = await reader.readexactly(int(headers.get("content-length", "0")))
            path, _, raw = target.partition("?")
            query = dict(pair.split("=", 1) for pair in raw.split("&") if "=" in pair)
            handler = routes.get((method, path))
            if handler is None:
                log("%s %s: no such route" % (method, target))
                status, payload, kind = 404, b"", "text/plain"
            else:
                status, payload, kind = handler(query, body)
            writer.write(
                ("HTTP/1.1 %d OK\r\nContent-Type: %s\r\nContent-Length: %d\r\n\r\n" % (status, kind, len(payload))).encode()
                + payload
            )
            await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionResetError):
        pass
    finally:
        writer.close()


async def main(port, name, address):
    state = Playback()
    routes = build(state)
    await asyncio.start_server(lambda r, w: serve(routes, r, w), "0.0.0.0", port)
    zeroconf = AsyncZeroconf(ip_version=IPVersion.V4Only)
    info = AsyncServiceInfo(
        "_airplay._tcp.local.",
        "%s._airplay._tcp.local." % name,
        addresses=[socket.inet_aton(address)],
        port=port,
        properties={
            "deviceid": "AA:BB:CC:00:00:01",
            "features": "0x1F",
            "model": "AppleTV3,2",
            "srcvers": "220.68",
            "flags": "0x4",
        },
        server="totum-standin.local.",
    )
    await zeroconf.async_register_service(info)
    log("AirPlay v1 stand-in %r listening on %d (silent: it fetches the stream and plays nothing)" % (name, port))
    await asyncio.Event().wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7011)
    parser.add_argument("--name", default="Totum-AirPlay-Standin")
    parser.add_argument("--address", required=True, help="a local non-loopback address: mDNS is not answered on 127.0.0.1")
    arguments = parser.parse_args()
    asyncio.run(main(arguments.port, arguments.name, arguments.address))
