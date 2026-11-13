#!/usr/bin/env python3
import json
import os
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8099
TOTUM = os.environ.get("TOTUM_URL", "http://127.0.0.1:8772")
RESOLVER = os.environ.get("SHOW_RESOLVER", "http://127.0.0.1:8100/resolve")
REQUESTER = {"id": "home-assistant", "label": "🏠 Home Assistant"}
SPEAKERS = {
    "soundbar": {"id": "54158976cda4", "label": "LG S65Q soundbar"},
    "soundmic": {"id": "f44efd21926d", "label": "dprofy Soundmic"},
}
DEFAULT_SPEAKER = "soundbar"
SOUNDS = {"brown-noise": "brown-noise.opus"}
STATIONS = {"jazz": "https://www.youtube.com/watch?v=Dx5qFachd3A"}
STATUS_TTL = 4
TOTUM_TIMEOUT = 90
START_POLL = 1
START_WAIT = 150
POLL_LOG_PERIOD = 900


def log(message):
    print(f"dewidebug audio-ctl {message}", flush=True)


def video_id(url):
    query = parse_qs(urlparse(url).query)
    return (query.get("v") or [url.rstrip("/").rsplit("/", 1)[-1]])[0]


def sound_item(name):
    return {"itemId": f"sound:{name}", "sourceId": "hermes-audio", "title": name.replace("-", " ").capitalize(),
            "contentKind": "MUSIC", "playbackType": "PODCAST", "handle": f"path=file:///sounds/{SOUNDS[name]}"}


def station_item(name):
    url = STATIONS[name]
    return {"itemId": video_id(url), "sourceId": "hermes-audio-stations", "title": f"{name.capitalize()} station",
            "contentKind": "LIVE", "playbackType": "VIDEO", "handle": url, "mediaUrl": url}


def show_item(show):
    url = show["url"]
    return {"itemId": show.get("id") or video_id(url), "sourceId": show.get("channel") or "hermes-audio-shows",
            "title": show.get("title") or "Show", "contentKind": "STANDARD", "playbackType": "VIDEO",
            "handle": url, "mediaUrl": url}


def queue_request(item, loop):
    return {"items": [item], "mode": "PLAY", "loop": loop, "from": REQUESTER}


def control(action, **fields):
    return {"action": action, **fields}


def speaker_of_target(target_id):
    return next((name for name, spk in SPEAKERS.items() if spk["id"] == target_id), None)


def mine(entry):
    return entry.get("addedBy") == REQUESTER["label"]


def my_indices(state):
    return [i for i, entry in enumerate(state.get("entries", [])) if mine(entry)]


def current_entry(state):
    entries, at = state.get("entries", []), state.get("at", -1)
    return entries[at] if 0 <= at < len(entries) else None


def states_by_speaker(targets_json):
    by_id = {t["target"]["id"]: t for t in targets_json.get("targets", [])}
    return {name: by_id.get(spk["id"]) for name, spk in SPEAKERS.items()}


def holds(state):
    entry = current_entry(state)
    return bool(entry and mine(entry))


def holder(targets_json, selected):
    states = states_by_speaker(targets_json)
    order = [selected] + [n for n in SPEAKERS if n != selected]
    holding = [n for n in order if states[n] and holds(states[n])]
    playing = [n for n in holding if states[n].get("playing")]
    return (playing or holding or [selected])[0]


def media_of(entry):
    item = entry["item"]
    for name, url in STATIONS.items():
        if item.get("itemId") == video_id(url):
            return {"station": name, "title": name, "url": url}
    if item.get("itemId", "").startswith("sound:"):
        return None
    return {"title": item.get("title"), "url": item.get("handle") or item.get("mediaUrl")}


def status_from(targets_json, selected, last_error=None, starting=False):
    speaker = holder(targets_json, selected)
    state = states_by_speaker(targets_json).get(speaker)
    entry = current_entry(state) if state else None
    ours = entry is not None and mine(entry)
    playing = bool(ours and state.get("playing"))
    item_id = entry["item"].get("itemId", "") if ours else ""
    problem = state.get("problem") if state and my_indices(state) else None
    return {"state": "playing" if playing else ("starting" if starting else "stopped"),
            "starting": starting,
            "want": item_id[len("sound:"):] if item_id.startswith("sound:") else None,
            "media": media_of(entry) if ours else None,
            "bonded": state is not None, "link": bool(state and state.get("linked")),
            "keepalive": False, "holding_silence": False,
            "last_error": last_error or problem,
            "sounds": list(SOUNDS), "stations": list(STATIONS),
            "speaker": speaker, "speakers": list(SPEAKERS)}


class TotumError(Exception):
    pass


class Totum:
    def __init__(self, base=TOTUM, timeout=TOTUM_TIMEOUT):
        self.base, self.timeout = base.rstrip("/"), timeout

    def _call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as reply:
                return json.loads(reply.read())
        except urllib.error.HTTPError as err:
            text = err.read().decode(errors="replace")
            try:
                text = json.loads(text).get("error", text)
            except ValueError:
                pass
            raise TotumError(f"Totum answered {err.code}: {text}") from err
        except (urllib.error.URLError, OSError) as err:
            raise TotumError(f"Totum is unreachable: {err}") from err

    def targets(self):
        return self._call("GET", "/targets")

    def queue(self, target_id, body):
        return self._call("POST", f"/targets/queue?id={target_id}", body)

    def control(self, target_id, body):
        return self._call("POST", f"/targets/control?id={target_id}", body)


def resolve_show(channel, mode, vid_id=None):
    url = f"{RESOLVER}/{channel}?mode={mode}" + (f"&id={vid_id}" if vid_id else "")
    with urllib.request.urlopen(url, timeout=25) as reply:
        return json.loads(reply.read())


class Adapter:
    def __init__(self, totum=None, resolve=resolve_show, clock=time.time, sleep=time.sleep):
        self.totum, self.resolve, self.clock, self.sleep = totum or Totum(), resolve, clock, sleep
        self.lock = threading.Lock()
        self.pending = threading.Event()
        self.selected = DEFAULT_SPEAKER
        self.request = None
        self.starting = False
        self.last_error = None
        self.cache = (0.0, None)

    def _forget(self):
        with self.lock:
            self.cache = (0.0, None)

    def status(self):
        now = self.clock()
        with self.lock:
            at, value = self.cache
            if value is not None and now - at < STATUS_TTL:
                return value
            selected, error, starting = self.selected, self.last_error, self.starting or self.request is not None
        try:
            value = status_from(self.totum.targets(), selected, error, starting)
        except Exception as err:  # noqa: BLE001
            log(f"status: could not read Totum's targets: {type(err).__name__} {err}")
            value = {"state": "stopped", "starting": starting, "want": None, "media": None, "bonded": False,
                     "link": False, "keepalive": False, "holding_silence": False,
                     "last_error": f"{type(err).__name__}: {err}", "sounds": list(SOUNDS),
                     "stations": list(STATIONS), "speaker": selected, "speakers": list(SPEAKERS)}
        with self.lock:
            self.cache = (now, value)
        return value

    def _choose_speaker(self, query):
        name = query.get("speaker", [None])[0]
        if name is None:
            return True, None
        if name not in SPEAKERS:
            return False, {"state": "error", "error": f"unknown speaker {name!r}", "known": list(SPEAKERS)}
        with self.lock:
            self.selected = name
        return True, None

    def _submit(self, request):
        with self.lock:
            self.request = request
            self.starting = True
        self._forget()
        self.pending.set()

    def handle(self, path, query):
        path = path.split("?")[0].rstrip("/")
        if path in ("", "/status"):
            return 200, self.status()
        if path.startswith("/speaker/"):
            ok, err = self._choose_speaker({"speaker": [path[len("/speaker/"):]]})
            if not ok:
                return 400, err
            self._submit({"kind": "move"})
            return 200, {"speaker": self.selected}
        if path == "/stop":
            self._submit({"kind": "stop"})
            return 200, {"state": "stopped"}
        if path.startswith("/play-show"):
            return self._play_show(path, query)
        if path.startswith("/play"):
            return self._play(path, query)
        if path in ("/keepalive/on", "/keepalive/off"):
            return 200, {"keepalive": False, "note": "Totum owns the speakers; there is nothing to keep alive here"}
        return 404, {"error": "not found"}

    def _play(self, path, query):
        sound = (path[len("/play/"):] if path.startswith("/play/") else "") or "brown-noise"
        ok, err = self._choose_speaker(query)
        if not ok:
            return 400, err
        if sound in STATIONS:
            self._submit({"kind": "play", "item": station_item(sound), "loop": False})
            return 200, {"state": "starting", "station": sound}
        if sound not in SOUNDS:
            log(f"MISS sound={sound!r}")
            return 404, {"state": "error", "error": f"unknown sound {sound!r}", "known": list(SOUNDS) + list(STATIONS)}
        self._submit({"kind": "play", "item": sound_item(sound), "loop": True})
        return 200, {"state": "starting", "sound": sound}

    def _play_show(self, path, query):
        channel = path[len("/play-show/"):] or "msrachel"
        ok, err = self._choose_speaker(query)
        if not ok:
            return 400, err
        mode = query.get("mode", ["random"])[0]
        with self.lock:
            busy = self.starting or self.request is not None
        if busy:
            log("already starting, ignoring repeat tap")
            return 200, {"state": "already-starting", "channel": channel}
        self._submit({"kind": "show", "channel": channel, "mode": mode, "id": query.get("id", [None])[0]})
        return 200, {"state": "starting", "channel": channel, "mode": mode}

    def run_pending(self):
        with self.lock:
            request, self.request = self.request, None
        if request is None:
            return
        try:
            self._apply(request)
            error = None
        except Exception as err:  # noqa: BLE001
            error = str(err) if isinstance(err, TotumError) else f"{type(err).__name__}: {err}"
            log(f"{request['kind']} FAILED {error}")
        with self.lock:
            self.last_error = error
            self.starting = self.request is not None
        self._forget()

    def worker(self):
        while True:
            self.pending.wait()
            self.pending.clear()
            self.run_pending()

    def _apply(self, request):
        kind = request["kind"]
        if kind == "show":
            show = self.resolve(request["channel"], request["mode"], request.get("id"))
            log(f"show {request['channel']}/{request['mode']}: {show.get('title')}")
            self._play_item(show_item(show), False)
        elif kind == "play":
            self._play_item(request["item"], request["loop"])
        elif kind == "stop":
            self._stop_ours()
        elif kind == "move":
            self._move_ours()

    def _play_item(self, item, loop):
        with self.lock:
            speaker = self.selected
        target_id = SPEAKERS[speaker]["id"]
        targets = self.totum.targets()
        states = states_by_speaker(targets)
        for name, state in states.items():
            if name != speaker and state and my_indices(state):
                log(f"speaker {name} -> {speaker}: releasing the old one (stopped={self._release(name, state)})")
        here = states.get(speaker)
        entry = current_entry(here) if here else None
        if entry and mine(entry) and entry["item"].get("itemId") == item["itemId"] and here.get("playing"):
            log(f"{item['itemId']} is already playing on {speaker}")
            return
        log(f"play {item['itemId']} on {speaker} ({target_id}) loop={loop}")
        answered = self._started(target_id, self.totum.queue(target_id, queue_request(item, loop)))
        if answered.get("problem") and not answered.get("playing"):
            raise TotumError(answered["problem"])
        self._prune(target_id, answered, keep=answered.get("at"))

    def _started(self, target_id, answered):
        waited = 0
        while answered.get("starting") and waited < START_WAIT:
            self.sleep(START_POLL)
            waited += START_POLL
            listed = next((s for s in self.totum.targets().get("targets", []) if s["target"]["id"] == target_id), None)
            if listed is not None:
                answered = listed
        if answered.get("starting"):
            log(f"{target_id} was still starting after {waited}s; going on with what it said")
        else:
            log(f"{target_id} finished starting after {waited}s: playing={answered.get('playing')} "
                f"trouble={(answered.get('trouble') or {}).get('kind')}")
        return answered

    def _release(self, name, state):
        stopped = holds(state) and bool(state.get("playing"))
        if stopped:
            self.totum.control(SPEAKERS[name]["id"], control("STOP"))
        self._prune(SPEAKERS[name]["id"], state, keep=None)
        return stopped

    def _prune(self, target_id, state, keep):
        entries = state.get("entries", [])
        for index in sorted(my_indices(state), reverse=True):
            if index != keep:
                self.totum.control(target_id, control("REMOVE", index=index, itemId=entries[index]["item"]["itemId"]))

    def _stop_ours(self):
        for name, state in states_by_speaker(self.totum.targets()).items():
            if state and my_indices(state):
                log(f"stop on {name} (stopped={self._release(name, state)})")

    def _move_ours(self):
        with self.lock:
            speaker = self.selected
        states = states_by_speaker(self.totum.targets())
        for name, state in states.items():
            entry = current_entry(state) if state else None
            if name != speaker and entry and mine(entry) and state.get("playing"):
                log(f"moving {entry['item'].get('itemId')} from {name} to {speaker}")
                self._play_item(entry["item"], bool(entry.get("loop")))
                return
        log(f"speaker is {speaker}; nothing of ours to move")


class Handler(BaseHTTPRequestHandler):
    adapter = None
    polls = {"n": 0, "since": time.time()}
    polls_lock = threading.Lock()

    def _handle(self):
        code, body = self.adapter.handle(self.path, parse_qs(urlparse(self.path).query))
        raw = json.dumps(body).encode()
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            log("client hung up before the reply")

    do_GET = _handle
    do_POST = _handle

    def log_message(self, fmt, *args):
        message = fmt % args
        if "/status" in message:
            now = time.time()
            due = None
            with self.polls_lock:
                self.polls["n"] += 1
                if now - self.polls["since"] >= POLL_LOG_PERIOD:
                    due = (self.polls["n"], now - self.polls["since"])
                    self.polls.update(n=0, since=now)
            if due:
                log("%d /status polls in %ds" % (due[0], int(due[1])))
            return
        log("%s - %s" % (self.address_string(), message))


def main():
    adapter = Adapter()
    Handler.adapter = adapter
    threading.Thread(target=adapter.worker, daemon=True).start()
    log(f"listening on 127.0.0.1:{PORT}, playing through Totum at {TOTUM}, speakers={list(SPEAKERS)} "
        f"sounds={list(SOUNDS)} stations={list(STATIONS)}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
