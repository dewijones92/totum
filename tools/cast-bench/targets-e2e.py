#!/usr/bin/env python3
import argparse
import json
import sys
import time
import urllib.request

ITEM = {
    "itemId": "sound:tone",
    "sourceId": "e2e",
    "title": "Tone",
    "contentKind": "MUSIC",
    "playbackType": "PODCAST",
    "handle": "path=file:///sounds/tone.mp3",
}


def video_item(url, duration_ms):
    name = url.rsplit("/", 1)[-1]
    return {
        "itemId": "video:" + name,
        "sourceId": "e2e",
        "title": name,
        "contentKind": "STANDARD",
        "playbackType": "PODCAST",
        "mediaUrl": url,
        "durationMs": duration_ms,
    }


def call(base, path, body=None):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"},
        method="POST" if body is not None else "GET",
    )
    with urllib.request.urlopen(request, timeout=60) as reply:
        return json.loads(reply.read())


def state(base, target):
    found = [s for s in call(base, "/targets")["targets"] if s["target"]["id"] == target]
    return found[0] if found else None


def show(label, s):
    print(
        "%s  %-34s playing=%-5s at=%-2s pos=%6sms dur=%s vol=%s starting=%s problem=%s trouble=%s"
        % (
            time.strftime("%H:%M:%S"),
            label,
            s["playing"],
            s["at"],
            s["positionMs"],
            s.get("durationMs"),
            s.get("volume"),
            s.get("starting"),
            s.get("problem"),
            (s.get("trouble") or {}).get("kind"),
        ),
        flush=True,
    )


def settle(base, target, label, seconds):
    end = time.time() + seconds
    last = None
    while time.time() < end:
        last = state(base, target)
        show(label, last)
        time.sleep(1.5)
    return last


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8772")
    parser.add_argument("--target", required=True)
    parser.add_argument("--volume-check", action="store_true", help="also set a volume and read it back")
    parser.add_argument("--video-url", help="queue this video (an address the Pi can read, byte ranges) instead of the tone")
    parser.add_argument("--duration-ms", type=int, default=30000, help="the video's length, when --video-url is given")
    arguments = parser.parse_args()
    base, target = arguments.base, arguments.target

    listed = [(s["target"]["id"], s["target"]["kind"], s["target"]["label"]) for s in call(base, "/targets")["targets"]]
    print("listed:", listed, flush=True)
    before = state(base, target)
    print("volume before any play:", before.get("volume"), flush=True)

    item = video_item(arguments.video_url, arguments.duration_ms) if arguments.video_url else ITEM
    queued = call(base, "/targets/queue?id=" + target, {"items": [item], "mode": "PLAY"})
    show("queued (answer)", queued)
    settle(base, target, "playing", 8)

    call(base, "/targets/control?id=" + target, {"action": "PAUSE"})
    settle(base, target, "after PAUSE", 4)
    call(base, "/targets/control?id=" + target, {"action": "PLAY"})
    settle(base, target, "after PLAY (resume)", 4)
    seek_to = min(40000, arguments.duration_ms // 2) if arguments.video_url else 40000
    call(base, "/targets/control?id=" + target, {"action": "SEEK", "positionMs": seek_to})
    after_seek = settle(base, target, "after SEEK %d" % seek_to, 6)
    if arguments.volume_check:
        call(base, "/targets/control?id=" + target, {"action": "VOLUME", "volume": 10})
        settle(base, target, "after VOLUME 10", 3)
    call(base, "/targets/control?id=" + target, {"action": "STOP"})
    final = settle(base, target, "after STOP", 4)
    print("volume at the end:", final.get("volume"), "(before any play: %s)" % before.get("volume"), flush=True)
    moved = after_seek["positionMs"] >= seek_to
    print("RESULT seek moved the position:", moved, flush=True)
    return 0 if moved else 1


if __name__ == "__main__":
    sys.exit(main())
