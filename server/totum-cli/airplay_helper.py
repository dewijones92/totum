#!/usr/bin/env python3
import argparse
import asyncio
import json
import os
import sys

MS = 1000
POLL_SECONDS = 1.0
STOP_WAIT_SECONDS = 5
NEEDS_PAIRING = "AirPlay needs pairing first: run  totum airplay-pair %s"


def say(**event):
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def note(message):
    say(event="note", message=message)


class Credentials:
    def __init__(self, path):
        self.path = path

    def load(self):
        if not self.path:
            return None
        try:
            with open(self.path) as saved:
                return json.load(saved).get("credentials")
        except FileNotFoundError:
            return None
        except (OSError, ValueError) as problem:
            note("the AirPlay credentials in %s were unreadable (%s); playing without them" % (self.path, problem))
            return None

    def save(self, credentials, identifier, name):
        directory = os.path.dirname(self.path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        descriptor = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as saved:
            json.dump({"credentials": credentials, "identifier": identifier, "name": name}, saved)
        os.chmod(self.path, 0o600)


class PyatvDevice:
    def __init__(self, host, credentials):
        self.host = host
        self.credentials = credentials
        self.name = None
        self.model = None
        self.connection = None
        self.play_task = None
        self.atv = None
        self.config = None

    async def open(self):
        import pyatv
        from pyatv.const import Protocol
        import pyatv.protocols.airplay as airplay

        loop = asyncio.get_running_loop()
        found = await pyatv.scan(loop, hosts=[self.host], timeout=5)
        config = next((c for c in found if c.get_service(Protocol.AirPlay)), None)
        if config is None:
            raise RuntimeError("no AirPlay receiver answered at " + self.host)
        if self.credentials:
            config.set_credentials(Protocol.AirPlay, self.credentials)
        self.config = config
        self.name = config.name
        self.model = str(config.device_info.model)
        self.atv = await pyatv.connect(config, loop)
        original = airplay.http_connect

        async def spy(address, port):
            self.connection = await original(address, port)
            return self.connection

        airplay.http_connect = spy

    def start(self, url, start_seconds):
        self.connection = None
        self.play_task = asyncio.ensure_future(self.atv.stream.play_url(url, position=start_seconds))
        return self.play_task

    async def info(self):
        import plistlib

        connection = self.connection
        if connection is None:
            return None
        response = await connection.get("/playback-info", allow_error=True)
        if not response.body:
            return {}
        return plistlib.loads(response.body if isinstance(response.body, bytes) else response.body.encode())

    async def rate(self, value):
        if self.connection is not None:
            await self.connection.post("/rate?value=%f" % value, allow_error=True)

    async def scrub(self, seconds):
        if self.connection is not None:
            await self.connection.post("/scrub?position=%f" % seconds, allow_error=True)

    async def halt(self):
        if self.atv is not None:
            await self.atv.remote_control.stop()

    async def close(self):
        if self.atv is not None:
            await self.halt()
            tasks = self.atv.close()
            if tasks:
                await asyncio.wait(tasks, timeout=2)


class Player:
    def __init__(self, device, host):
        self.device = device
        self.host = host
        self.url = None
        self.stopping = False
        self.watcher = None
        self.last = {"position": 0.0, "duration": None, "rate": 0.0}

    def status(self, state, idle=None):
        say(
            event="status",
            state=state,
            idle=idle,
            positionMs=int((self.last["position"] or 0) * MS),
            durationMs=int(self.last["duration"] * MS) if self.last["duration"] else None,
            contentId=self.url,
        )

    async def load(self, command):
        await self.halt("a new item is being loaded")
        self.url = command["url"]
        self.stopping = False
        self.last = {"position": 0.0, "duration": None, "rate": 0.0}
        started = self.device.start(self.url, command.get("startMs", 0) / MS)
        note("load of %s started at %.1fs" % (command.get("title"), command.get("startMs", 0) / MS))
        self.status("BUFFERING")
        self.watcher = asyncio.ensure_future(self.watch(started, self.url))

    async def watch(self, started, url):
        poller = asyncio.ensure_future(self.poll(url))
        try:
            await started
            outcome = ("IDLE", "FINISHED")
        except asyncio.CancelledError:
            outcome = None
        except Exception as problem:
            say(event="error", message=self.explain(problem))
            outcome = ("IDLE", "ERROR")
        poller.cancel()
        if outcome and not self.stopping and url == self.url:
            self.status(*outcome)

    def explain(self, problem):
        text = "%s: %s" % (type(problem).__name__, problem)
        if type(problem).__name__ == "AuthenticationError":
            return NEEDS_PAIRING % self.host + " (" + text + ")"
        return text

    async def poll(self, url):
        while url == self.url and not self.stopping:
            await asyncio.sleep(POLL_SECONDS)
            try:
                info = await self.device.info()
            except Exception as problem:
                note("playback-info failed: %s" % problem)
                continue
            if not info:
                continue
            duration = info.get("duration")
            rate = info.get("rate", 0.0)
            self.last = {"position": info.get("position", self.last["position"]), "duration": duration, "rate": rate}
            if duration is None:
                self.status("BUFFERING")
            else:
                self.status("PLAYING" if rate and rate > 0 else "PAUSED")

    async def halt(self, why):
        task = self.device.play_task
        if task is None or task.done():
            return
        self.stopping = True
        note("stopping the current item: " + why)
        await self.device.halt()
        try:
            await asyncio.wait_for(asyncio.shield(task), STOP_WAIT_SECONDS)
        except Exception:
            task.cancel()

    async def command(self, command):
        name = command["cmd"]
        if name == "load":
            await self.load(command)
        elif name == "pause":
            await self.device.rate(0.0)
            self.last["rate"] = 0.0
        elif name == "resume":
            await self.device.rate(1.0)
        elif name == "seek":
            await self.device.scrub(command["positionMs"] / MS)
            self.last["position"] = command["positionMs"] / MS
        elif name == "volume":
            note(
                "volume %s%% not sent: AirPlay URL playback has no volume of its own, the TV's volume is the TV's"
                % command.get("percent")
            )
        elif name == "stop":
            await self.halt("stop")
        elif name == "release":
            await self.halt("release")
            say(event="handback", quit=False, why="AirPlay playback ended; nothing of Totum's is left open")


async def run(host, credentials_path, device=None):
    credentials = Credentials(credentials_path)
    device = device or PyatvDevice(host, credentials.load())
    try:
        await device.open()
    except Exception as problem:
        say(event="error", message="%s: %s" % (type(problem).__name__, problem))
        return 2
    if not credentials.load():
        note("no AirPlay credentials stored for %s; a receiver that requires pairing will refuse" % host)
    say(event="ready", name=device.name, model=device.model)
    player = Player(device, host)
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            break
        try:
            command = json.loads(line)
            if command["cmd"] == "quit":
                break
            await player.command(command)
        except Exception as problem:
            say(event="error", message="%s: %s" % (type(problem).__name__, problem))
    await device.close()
    return 0


async def pair(host, credentials_path, pin, name):
    import pyatv
    from pyatv.const import Protocol

    loop = asyncio.get_running_loop()
    found = await pyatv.scan(loop, hosts=[host], timeout=5)
    config = next((c for c in found if c.get_service(Protocol.AirPlay)), None)
    if config is None:
        print("No AirPlay receiver answered at %s." % host, file=sys.stderr)
        return 2
    print("Pairing with %s (%s) over AirPlay." % (config.name, host))
    pairing = await pyatv.pair(config, Protocol.AirPlay, loop, name=name)
    try:
        await pairing.begin()
        if pairing.device_provides_pin:
            code = pin or input("Enter the PIN shown on the screen: ").strip()
            pairing.pin(code)
        else:
            print("The receiver shows no PIN; pairing without one.")
        await pairing.finish()
        if not pairing.has_paired:
            print("Pairing did not complete.", file=sys.stderr)
            return 1
        Credentials(credentials_path).save(pairing.service.credentials, config.identifier, config.name)
        print("Paired. The credentials were stored in %s (mode 600); they are never logged." % credentials_path)
        return 0
    finally:
        await pairing.close()


def main(argv=None):
    parser = argparse.ArgumentParser(prog="airplay_helper")
    sub = parser.add_subparsers(dest="mode")
    serve = sub.add_parser("run")
    serve.add_argument("host")
    serve.add_argument("credentials", nargs="?")
    pairing = sub.add_parser("pair")
    pairing.add_argument("host")
    pairing.add_argument("--credentials", required=True)
    pairing.add_argument("--pin")
    pairing.add_argument("--name", default="Totum")
    args = parser.parse_args(argv)
    if args.mode == "pair":
        return asyncio.run(pair(args.host, args.credentials, args.pin, args.name))
    return asyncio.run(run(args.host, args.credentials))


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] not in ("run", "pair", "-h", "--help"):
        args = ["run"] + args
    sys.exit(main(args))
