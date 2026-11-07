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


class FakeCast:
    def __init__(self, app_id):
        self.app_id = app_id
        self.quit = 0

    def quit_app(self):
        self.quit += 1
        self.app_id = None


YOUTUBE = "233637DE"
BACKDROP = "E8C28D3C"
DEFAULT_MEDIA_RECEIVER = "CC1AD845"


class HandbackTest(unittest.TestCase):
    def setUp(self):
        self.handback = chromecast_helper.Handback()

    def quiet(self, call, *args):
        with mock.patch.object(sys, "stdout", io.StringIO()):
            return call(*args)

    def test_an_app_totum_launched_on_an_idle_tv_is_quit_on_release(self):
        cast = FakeCast(None)
        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER

        said = self.quiet(self.handback.release, cast)

        self.assertEqual(1, cast.quit)
        self.assertTrue(said["quit"])

    def test_the_backdrop_counts_as_idle(self):
        cast = FakeCast(BACKDROP)
        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER

        self.quiet(self.handback.release, cast)

        self.assertEqual(1, cast.quit)

    def test_an_app_that_was_running_before_totum_is_left_alone(self):
        cast = FakeCast(YOUTUBE)
        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER

        said = self.quiet(self.handback.release, cast)

        self.assertEqual(0, cast.quit)
        self.assertFalse(said["quit"])

    def test_a_second_load_in_the_same_session_keeps_the_first_answer(self):
        cast = FakeCast(None)
        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER
        self.quiet(self.handback.loading, cast)

        self.quiet(self.handback.release, cast)

        self.assertEqual(1, cast.quit)

    def test_a_load_over_another_app_forgets_that_the_tv_was_idle(self):
        cast = FakeCast(None)
        self.quiet(self.handback.loading, cast)
        cast.app_id = YOUTUBE
        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER

        self.quiet(self.handback.release, cast)

        self.assertEqual(0, cast.quit)

    def test_something_else_on_screen_at_release_is_never_quit(self):
        cast = FakeCast(None)
        self.quiet(self.handback.loading, cast)
        cast.app_id = YOUTUBE

        said = self.quiet(self.handback.release, cast)

        self.assertEqual(0, cast.quit)
        self.assertFalse(said["quit"])

    def test_a_release_with_nothing_loaded_does_nothing(self):
        cast = FakeCast(DEFAULT_MEDIA_RECEIVER)

        said = self.quiet(self.handback.release, cast)

        self.assertEqual(0, cast.quit)
        self.assertFalse(said["quit"])

    def test_a_default_receiver_already_running_before_the_first_load_is_not_ours(self):
        cast = FakeCast(DEFAULT_MEDIA_RECEIVER)
        self.quiet(self.handback.loading, cast)

        self.quiet(self.handback.release, cast)

        self.assertEqual(0, cast.quit)

    def test_after_a_quit_the_next_session_decides_afresh(self):
        cast = FakeCast(None)
        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER
        self.quiet(self.handback.release, cast)
        self.quiet(self.handback.release, cast)
        self.assertEqual(1, cast.quit)

        self.quiet(self.handback.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER
        self.quiet(self.handback.release, cast)

        self.assertEqual(2, cast.quit)


class QuitFailureTest(unittest.TestCase):
    def test_a_quit_that_fails_is_reported_not_raised(self):
        cast = FakeCast(None)
        cast.quit_app = mock.Mock(side_effect=RuntimeError("socket closed"))
        handback = chromecast_helper.Handback()
        with mock.patch.object(sys, "stdout", io.StringIO()):
            handback.loading(cast)
            cast.app_id = DEFAULT_MEDIA_RECEIVER
            said = handback.release(cast)

        self.assertFalse(said["quit"])
        self.assertIn("socket closed", said["why"])


if __name__ == "__main__":
    unittest.main()
