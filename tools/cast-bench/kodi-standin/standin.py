#!/usr/bin/env python3
import argparse
import base64
import http.server
import json
import os
import subprocess
import sys
import threading
import time


def log(message):
    sys.stdout.write("%s kodi-standin %s\n" % (time.strftime("%H:%M:%S"), message))
    sys.stdout.flush()


def read_login(path):
    pairs = {}
    with open(path) as handle:
        for line in handle:
            if "=" in line:
                key, value = line.rstrip("\n").split("=", 1)
                pairs[key.strip()] = value.strip()
    return pairs.get("user"), pairs.get("password")


class Player:
    def __init__(self, ffmpeg, ffprobe):
        self.ffmpeg = ffmpeg
        self.ffprobe = ffprobe
        self.lock = threading.Lock()
        self.process = None
        self.url = None
        self.offset = 0.0
        self.position = 0.0
        self.total = 0.0
        self.paused = False
        self.running = False
        self.streams = []

    def probe(self, url):
        answer = subprocess.run(
            [self.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", url],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if answer.returncode != 0:
            log("ffprobe could not open %s: %s" % (url, answer.stderr.strip()[:300]))
            return None
        found = json.loads(answer.stdout)
        self.streams = found.get("streams", [])
        for stream in self.streams:
            if stream.get("codec_type") == "video":
                log("stream: video %s %sx%s" % (stream.get("codec_name"), stream.get("width"), stream.get("height")))
            else:
                log("stream: %s %s %s Hz" % (stream.get("codec_type"), stream.get("codec_name"), stream.get("sample_rate")))
        return float(found.get("format", {}).get("duration") or 0)

    def open(self, url):
        self.stop()
        self.url = url
        self.offset = 0.0
        self.position = 0.0
        self.paused = False
        self.total = self.probe(url) or 0.0
        log("Player.Open %s, %.1fs long, %d stream(s)" % (url, self.total, len(self.streams)))
        self.start(0.0)

    def start(self, at):
        self.offset = at
        self.position = at
        command = [self.ffmpeg, "-v", "error", "-re", "-i", self.url, "-ss", "%.3f" % at, "-map", "0", "-f", "null",
                   "-progress", "pipe:1", "-nostats", "-"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with self.lock:
            self.process = process
            self.running = True
        threading.Thread(target=self.follow, args=(process,), daemon=True).start()

    def follow(self, process):
        for line in process.stdout:
            if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
                value = line.split("=", 1)[1].strip()
                if value.lstrip("-").isdigit():
                    with self.lock:
                        if process is self.process:
                            self.position = self.offset + max(0, int(value)) / 1_000_000.0
        code = process.wait()
        errors = process.stderr.read().strip()
        with self.lock:
            if process is not self.process:
                return
            self.running = False
        if code == 0 and not errors:
            log("playback reached the end at %.1fs" % self.position)
        else:
            log("playback failed (exit %s): %s" % (code, errors[:400]))

    def pause(self, play):
        if not self.running and not self.paused:
            return
        if not play and not self.paused:
            with self.lock:
                process = self.process
                self.process = None
                self.running = False
                held = self.position
            if process is not None:
                process.kill()
            self.paused = True
            self.position = held
            log("paused at %.1fs (the decode is dropped, so resuming does not catch up on wall time)" % held)
        elif play and self.paused:
            self.paused = False
            log("resumed at %.1fs" % self.position)
            self.start(self.position)

    def seek(self, to):
        with self.lock:
            process = self.process
            self.process = None
        if process is not None:
            process.kill()
        self.paused = False
        log("seek to %.1fs" % to)
        self.start(to)

    def stop(self):
        with self.lock:
            process = self.process
            self.process = None
            self.running = False
        if process is not None:
            process.kill()
            log("stopped at %.1fs" % self.position)
        self.paused = False


def milliseconds(parts):
    return ((parts["hours"] * 60 + parts["minutes"]) * 60 + parts["seconds"]) * 1000 + parts["milliseconds"]


def time_parts(seconds):
    ms = int(seconds * 1000)
    return {"hours": ms // 3_600_000, "minutes": ms // 60_000 % 60, "seconds": ms // 1000 % 60, "milliseconds": ms % 1000}


def build(arguments, player):
    state = {"volume": arguments.volume}

    def handle(method, params):
        if method == "JSONRPC.Version":
            return {"version": {"major": 13, "minor": 5, "patch": 0}}
        if method == "Addons.GetAddons":
            addons = [{"addonid": "plugin.video.stand-in", "type": "xbmc.python.pluginsource"}]
            if arguments.adaptive:
                addons.append({"addonid": "inputstream.adaptive", "type": "kodi.inputstream"})
            return {"addons": addons}
        if method == "Application.GetProperties":
            return {"volume": state["volume"], "version": {"major": 21, "minor": 3}}
        if method == "Application.SetVolume":
            state["volume"] = params["volume"]
            log("VOLUME written: %s (only the slider does this)" % params["volume"])
            return state["volume"]
        if method == "Player.GetActivePlayers":
            active = player.running or player.paused
            return [{"playerid": 1, "playertype": "internal", "type": "video"}] if active else []
        if method == "Player.GetProperties":
            return {
                "time": time_parts(player.position),
                "totaltime": time_parts(player.total),
                "speed": 0 if player.paused else 1,
            }
        if method == "Player.Open":
            threading.Thread(target=player.open, args=(params["item"]["file"],), daemon=True).start()
            return "OK"
        if method == "Player.PlayPause":
            player.pause(params["play"] is True)
            return {"speed": 0 if player.paused else 1}
        if method == "Player.Seek":
            player.seek(milliseconds(params["value"]["time"]) / 1000.0)
            return {"time": time_parts(player.position)}
        if method == "Player.Stop":
            player.stop()
            return "OK"
        raise KeyError(method)

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
            user, password = read_login(arguments.credentials)
            expected = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
            if self.headers.get("Authorization") != expected:
                log("%s refused: the sign-in did not match" % body.get("method"))
                self.send_response(401)
                self.end_headers()
                return
            method, params = body.get("method"), body.get("params") or {}
            if method not in ("Player.GetActivePlayers", "Player.GetProperties", "JSONRPC.Version"):
                log("rpc %s %s" % (method, json.dumps(params)))
            try:
                reply = {"jsonrpc": "2.0", "id": body.get("id"), "result": handle(method, params)}
            except KeyError:
                reply = {"jsonrpc": "2.0", "id": body.get("id"), "error": {"code": -32601, "message": "Method not found."}}
            payload = json.dumps(reply).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


def main():
    parser = argparse.ArgumentParser(
        description="A Kodi JSON-RPC stand-in: plays nothing audibly, opens what it is given with ffmpeg (Kodi's own "
        "HLS demuxer) into a null sink, and reports what it found."
    )
    parser.add_argument("--credentials", required=True, help="a file of user=/password= lines; read at each call")
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--adaptive", action="store_true", help="list inputstream.adaptive among the add-ons")
    parser.add_argument("--volume", type=int, default=40)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    arguments = parser.parse_args()
    if not os.path.isfile(arguments.credentials):
        sys.exit("no credentials file at " + arguments.credentials)
    player = Player(arguments.ffmpeg, arguments.ffprobe)
    server = http.server.ThreadingHTTPServer((arguments.bind, arguments.port), build(arguments, player))
    log("Kodi stand-in on %s:%d (adaptive add-on %s); silent: ffmpeg decodes into a null sink"
        % (arguments.bind, arguments.port, "listed" if arguments.adaptive else "absent"))
    server.serve_forever()


if __name__ == "__main__":
    main()
