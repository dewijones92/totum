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


if __name__ == "__main__":
    unittest.main()
