#!/usr/bin/env python3
import json
import sys
import threading

import pychromecast

MS = 1000


def say(**event):
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


class Listener:
    def __init__(self, cast):
        self.cast = cast

    def new_media_status(self, status):
        say(
            event="status",
            state=status.player_state,
            idle=status.idle_reason,
            positionMs=int((status.current_time or 0) * MS),
            durationMs=int(status.duration * MS) if status.duration else None,
            contentId=status.content_id,
        )


def connect(host):
    casts, browser = pychromecast.get_chromecasts(known_hosts=[host], timeout=15)
    if not casts:
        say(event="error", message="no Chromecast answered at " + host)
        sys.exit(2)
    cast = casts[0]
    cast.wait(timeout=20)
    cast.media_controller.register_status_listener(Listener(cast))
    return cast, browser


def keep_reporting(cast):
    def later():
        timer = threading.Timer(5, tick)
        timer.daemon = True
        timer.start()

    def tick():
        status = cast.media_controller.status
        if status is not None and status.player_state == "PLAYING":
            Listener(cast).new_media_status(status)
        later()

    later()


def main():
    host = sys.argv[1]
    cast, browser = connect(host)
    media = cast.media_controller
    say(event="ready", name=cast.name, model=cast.model_name)
    keep_reporting(cast)
    for line in sys.stdin:
        try:
            command = json.loads(line)
            name = command["cmd"]
            if name == "load":
                media.play_media(
                    command["url"],
                    command["type"],
                    title=command.get("title"),
                    current_time=command.get("startMs", 0) / MS,
                    autoplay=True,
                )
            elif name == "pause":
                media.pause()
            elif name == "resume":
                media.play()
            elif name == "seek":
                media.seek(command["positionMs"] / MS)
            elif name == "volume":
                cast.set_volume(command["percent"] / 100)
            elif name == "stop":
                media.stop()
            elif name == "quit":
                break
        except Exception as problem:
            say(event="error", message=str(problem))
    browser.stop_discovery()
    cast.disconnect()


if __name__ == "__main__":
    main()
