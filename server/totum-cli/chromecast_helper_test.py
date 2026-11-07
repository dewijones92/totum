import importlib
import io
import json
import os
import sys
import types
import unittest
from unittest import mock

sys.modules.setdefault("pychromecast", types.ModuleType("pychromecast"))
sys.path.insert(0, os.path.dirname(__file__))
chromecast_helper = importlib.import_module("chromecast_helper")


class Status:
    def __init__(self, current_time, adjusted, state="PLAYING"):
        self.player_state = state
        self.idle_reason = None
        self.current_time = current_time
        self.adjusted_current_time = adjusted
        self.duration = 20.0
        self.content_id = "http://pi/y/abc.mp4"


class StatusPositionTest(unittest.TestCase):
    def said(self, status):
        out = io.StringIO()
        with mock.patch.object(sys, "stdout", out):
            chromecast_helper.Listener(None).new_media_status(status)
        return json.loads(out.getvalue())

    def test_a_repeated_status_reports_where_playback_is_now_not_where_it_was(self):
        stale_since_start = Status(current_time=0.5, adjusted=12.3)

        self.assertEqual(12300, self.said(stale_since_start)["positionMs"])

    def test_a_paused_status_keeps_its_own_time(self):
        paused = Status(current_time=7.0, adjusted=7.0, state="PAUSED")

        self.assertEqual(7000, self.said(paused)["positionMs"])

    def test_a_status_without_an_adjusted_time_falls_back_to_the_reported_one(self):
        old = Status(current_time=4.2, adjusted=None)

        self.assertEqual(4200, self.said(old)["positionMs"])


class LoadTest(unittest.TestCase):
    def loaded(self, command):
        media = mock.Mock()
        chromecast_helper.load(media, command)
        return media.play_media.call_args

    def test_a_load_hands_the_receiver_the_type_and_stream_type_it_was_sent(self):
        call = self.loaded(
            {
                "url": "http://pi:8771/c/ab/live.mpd",
                "type": "application/dash+xml",
                "title": "Live",
                "startMs": 0,
                "streamType": "BUFFERED",
            }
        )

        self.assertEqual(("http://pi:8771/c/ab/live.mpd", "application/dash+xml"), call.args)
        self.assertEqual("BUFFERED", call.kwargs["stream_type"])

    def test_a_load_without_a_stream_type_is_buffered_like_the_phones(self):
        call = self.loaded({"url": "http://x/a.mp3", "type": "audio/mpeg"})

        self.assertEqual("BUFFERED", call.kwargs["stream_type"])
        self.assertEqual(0, call.kwargs["current_time"])


if __name__ == "__main__":
    unittest.main()
