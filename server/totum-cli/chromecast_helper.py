#!/usr/bin/env python3
import json
import sys
import threading

import pychromecast

MS = 1000


def say(**event):
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


IDLE_APPS = (None, "E8C28D3C")
DEFAULT_MEDIA_RECEIVER = "CC1AD845"


def note(message):
    say(event="note", message=message)


class Handback:
    def __init__(self):
        self.ours = None

    def loading(self, cast):
        app = cast.app_id
        if app == DEFAULT_MEDIA_RECEIVER:
            if self.ours is None:
                self.ours = False
                note("the Default Media Receiver was already open before Totum's first load; not Totum's to quit")
            return
        self.ours = app in IDLE_APPS
        note("before this load the TV ran %s, so the receiver app %s" % (
            app or "nothing",
            "is Totum's to quit on stop" if self.ours else "stays when Totum stops",
        ))

    def release(self, cast):
        ours, self.ours = self.ours, None
        app = cast.app_id
        if not ours:
            why = "Totum did not launch the app on the TV (%s); left it open" % (app or "nothing running")
            note(why)
            return {"quit": False, "why": why}
        if app != DEFAULT_MEDIA_RECEIVER:
            why = "the TV now shows %s, not Totum's receiver; left it open" % (app or "nothing")
            note(why)
            return {"quit": False, "why": why}
        try:
            cast.quit_app()
        except Exception as problem:
            why = "quitting the receiver app failed: %s" % problem
            note(why)
            return {"quit": False, "why": why}
        why = "quit the receiver app Totum launched"
        note(why)
        return {"quit": True, "why": why}


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
    handback = Handback()
    say(event="ready", name=cast.name, model=cast.model_name)
    if cast.status is not None:
        DeviceVolume().new_cast_status(cast.status)
    keep_reporting(cast)
    for line in sys.stdin:
        try:
            command = json.loads(line)
            name = command["cmd"]
            if name == "load":
                handback.loading(cast)
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
            elif name == "release":
                say(event="handback", **handback.release(cast))
            elif name == "quit":
                break
        except Exception as problem:
            say(event="error", message=str(problem))
    browser.stop_discovery()
    cast.disconnect()


if __name__ == "__main__":
    main()
