#!/usr/bin/env python3
import json
import sys
import threading

import pychromecast

MS = 1000


def say(**event):
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def now_of(status):
    adjusted = getattr(status, "adjusted_current_time", None)
    return adjusted if adjusted is not None else status.current_time


class Listener:
    def __init__(self, cast):
        self.cast = cast

    def new_media_status(self, status):
        say(
            event="status",
            state=status.player_state,
            idle=status.idle_reason,
            positionMs=int((now_of(status) or 0) * MS),
            durationMs=int(status.duration * MS) if status.duration else None,
            contentId=status.content_id,
        )


class DeviceVolume:
    def new_cast_status(self, status):
        level = getattr(status, "volume_level", None)
        if level is not None:
            say(event="volume", percent=round(level * 100))


def connect(host):
    casts, browser = pychromecast.get_chromecasts(known_hosts=[host], timeout=15)
    if not casts:
        say(event="error", message="no Chromecast answered at " + host)
        sys.exit(2)
    cast = casts[0]
    cast.wait(timeout=20)
    cast.media_controller.register_status_listener(Listener(cast))
    cast.register_status_listener(DeviceVolume())
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


def load(media, command):
    media.play_media(
        command["url"],
        command["type"],
        title=command.get("title"),
        current_time=command.get("startMs", 0) / MS,
        stream_type=command.get("streamType", "BUFFERED"),
        autoplay=True,
    )


def main():
    host = sys.argv[1]
    cast, browser = connect(host)
    media = cast.media_controller
    say(event="ready", name=cast.name, model=cast.model_name)
    if cast.status is not None:
        DeviceVolume().new_cast_status(cast.status)
    keep_reporting(cast)
    for line in sys.stdin:
        try:
            command = json.loads(line)
            name = command["cmd"]
            if name == "load":
                load(media, command)
            elif name == "pause":
                media.pause()
            elif name == "resume":
                media.play()
            elif name == "seek":
                media.seek(command["positionMs"] / MS)
            elif name == "volume":
                cast.set_volume(command["percent"] / 100)
            elif name == "stop":
                if media.status is not None and media.status.media_session_id is not None:
                    media.stop()
            elif name == "quit":
                break
        except Exception as problem:
            say(event="error", message=str(problem))
    browser.stop_discovery()
    cast.disconnect()


if __name__ == "__main__":
    main()
