import importlib
import io
import json
import os
import shutil
import sys
import tempfile
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


class RememberedHandbackTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "handback-lg.json")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def quiet(self, call, *args):
        with mock.patch.object(sys, "stdout", io.StringIO()):
            return call(*args)

    def test_an_app_totum_launched_is_still_quit_after_the_helper_restarts(self):
        cast = FakeCast(None)
        first = self.quiet(chromecast_helper.Handback, self.path)
        self.quiet(first.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER

        restarted = self.quiet(chromecast_helper.Handback, self.path)
        said = self.quiet(restarted.release, cast)

        self.assertEqual(1, cast.quit)
        self.assertTrue(said["quit"])

    def test_a_restarted_helper_that_remembers_nothing_leaves_the_app_open(self):
        cast = FakeCast(DEFAULT_MEDIA_RECEIVER)

        restarted = self.quiet(chromecast_helper.Handback, self.path)
        said = self.quiet(restarted.release, cast)

        self.assertEqual(0, cast.quit)
        self.assertFalse(said["quit"])

    def test_a_release_forgets_it_so_a_later_restart_does_not_quit_someone_elses_app(self):
        cast = FakeCast(None)
        first = self.quiet(chromecast_helper.Handback, self.path)
        self.quiet(first.loading, cast)
        cast.app_id = DEFAULT_MEDIA_RECEIVER
        self.quiet(first.release, cast)
        self.assertFalse(os.path.exists(self.path))

        restarted = self.quiet(chromecast_helper.Handback, self.path)
        self.quiet(restarted.release, cast)

        self.assertEqual(1, cast.quit)

    def test_an_app_that_was_running_before_totum_is_not_remembered_as_ours(self):
        cast = FakeCast(YOUTUBE)
        first = self.quiet(chromecast_helper.Handback, self.path)
        self.quiet(first.loading, cast)

        restarted = self.quiet(chromecast_helper.Handback, self.path)
        cast.app_id = DEFAULT_MEDIA_RECEIVER
        self.quiet(restarted.release, cast)

        self.assertEqual(0, cast.quit)

    def test_an_unreadable_file_is_ignored_and_said(self):
        with open(self.path, "w") as damaged:
            damaged.write("{not json")
        out = io.StringIO()

        with mock.patch.object(sys, "stdout", out):
            handback = chromecast_helper.Handback(self.path)

        self.assertIsNone(handback.ours)
        self.assertIn("unreadable", out.getvalue())


class ConnectionEventTest(unittest.TestCase):
    def said(self, status):
        out = io.StringIO()
        with mock.patch.object(sys, "stdout", out):
            chromecast_helper.Connection().new_connection_status(status)
        return json.loads(out.getvalue())

    def test_a_lost_connection_is_said_with_its_state(self):
        status = types.SimpleNamespace(status="LOST", address=("192.168.0.219", 8009))

        self.assertEqual({"event": "connection", "state": "LOST"}, self.said(status))

    def test_a_connected_one_is_said_too(self):
        status = types.SimpleNamespace(status="CONNECTED", address=None)

        self.assertEqual("CONNECTED", self.said(status)["state"])


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


class CaptionStatus(Status):
    def __init__(self, tracks, active=(), content="http://pi/c/ab/manifest.mpd"):
        super().__init__(current_time=1.0, adjusted=1.0)
        self.subtitle_tracks = tracks
        self.current_subtitle_tracks = list(active)
        self.content_id = content


def text(track_id, language, name="English"):
    return {"trackId": track_id, "type": "TEXT", "language": language, "name": name}


class TrackEventTest(unittest.TestCase):
    def setUp(self):
        chromecast_helper.TRACKS.last = None

    def said(self, *statuses):
        out = io.StringIO()
        with mock.patch.object(sys, "stdout", out):
            for status in statuses:
                chromecast_helper.Listener(None).new_media_status(status)
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def tracks_said(self, *statuses):
        return [event for event in self.said(*statuses) if event["event"] == "tracks"]

    def test_the_text_tracks_the_receiver_offers_are_said_once(self):
        status = CaptionStatus([text(1, "en"), text(2, "cy", "Cymraeg")], active=[1])

        said = self.tracks_said(status, status, status)

        self.assertEqual(1, len(said))
        self.assertEqual(
            [{"id": 1, "language": "en", "name": "English"}, {"id": 2, "language": "cy", "name": "Cymraeg"}],
            said[0]["tracks"],
        )
        self.assertEqual([1], said[0]["active"])

    def test_audio_and_video_tracks_are_not_captions(self):
        status = CaptionStatus([{"trackId": 7, "type": "AUDIO", "language": "en"}, text(1, "en")])

        self.assertEqual([1], [t["id"] for t in self.tracks_said(status)[0]["tracks"]])

    def test_a_change_of_the_active_track_is_said_again(self):
        off = CaptionStatus([text(1, "en")], active=[])
        on = CaptionStatus([text(1, "en")], active=[1])

        said = self.tracks_said(off, on)

        self.assertEqual([[], [1]], [event["active"] for event in said])

    def test_a_status_with_no_track_information_says_nothing_about_tracks(self):
        plain = Status(current_time=1.0, adjusted=1.0)

        self.assertEqual([], self.tracks_said(plain, plain))

    def test_tracks_that_disappear_are_said_so_the_phone_stops_offering_them(self):
        with_tracks = CaptionStatus([text(1, "en")])
        without = CaptionStatus([])

        said = self.tracks_said(with_tracks, without)

        self.assertEqual([[1], []], [[t["id"] for t in event["tracks"]] for event in said])


class ChooseCaptionsTest(unittest.TestCase):
    def chosen(self, track_id):
        media = mock.Mock()
        out = io.StringIO()
        with mock.patch.object(sys, "stdout", out):
            chromecast_helper.choose_captions(media, track_id)
        return media, out.getvalue()

    def test_a_track_is_switched_on(self):
        media, said = self.chosen(3)

        media.enable_subtitle.assert_called_once_with(3)
        media.disable_subtitle.assert_not_called()
        self.assertIn("track 3", said)

    def test_no_track_switches_captions_off(self):
        media, said = self.chosen(None)

        media.disable_subtitle.assert_called_once_with()
        media.enable_subtitle.assert_not_called()
        self.assertIn("switched off", said)


if __name__ == "__main__":
    unittest.main()
