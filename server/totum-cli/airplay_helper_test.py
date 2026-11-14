import asyncio
import importlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
airplay_helper = importlib.import_module("airplay_helper")


class AuthenticationError(Exception):
    pass


class FakeDevice:
    def __init__(self):
        self.calls = []
        self.play_task = None
        self.name = "Fake LG"
        self.model = "DeviceModel.Unknown"
        self.info_now = {}
        self.started = None

    async def open(self):
        self.calls.append("open")

    def start(self, url, start_seconds):
        self.calls.append("start %s %s" % (url, start_seconds))
        self.started = asyncio.get_running_loop().create_future()
        self.play_task = self.started
        return self.started

    async def info(self):
        return self.info_now

    async def rate(self, value):
        self.calls.append("rate %s" % value)

    async def scrub(self, seconds):
        self.calls.append("scrub %s" % seconds)

    async def halt(self):
        self.calls.append("halt")
        if self.started and not self.started.done():
            self.started.set_result(None)

    async def close(self):
        self.calls.append("close")


class Said:
    def __init__(self):
        self.out = io.StringIO()

    def events(self):
        return [json.loads(line) for line in self.out.getvalue().splitlines()]

    def of(self, kind):
        return [e for e in self.events() if e["event"] == kind]


class PlayerTest(unittest.TestCase):
    def setUp(self):
        self.said = Said()
        self.patch = mock.patch.object(sys, "stdout", self.said.out)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        mock.patch.object(airplay_helper, "POLL_SECONDS", 0.01).start()
        self.addCleanup(mock.patch.stopall)
        self.device = FakeDevice()
        self.player = airplay_helper.Player(self.device, "192.168.0.219")

    def run_async(self, steps):
        async def go():
            await steps()
            await asyncio.sleep(0.05)

        asyncio.run(go())

    def load(self, start_ms=0):
        return self.player.command(
            {"cmd": "load", "url": "http://pi/c/ab/hls.m3u8", "type": "x", "title": "Talk", "startMs": start_ms}
        )

    def test_a_load_starts_playing_at_its_own_second_and_says_it_is_buffering_for_that_item(self):
        async def steps():
            await self.load(start_ms=12500)

        self.run_async(steps)

        self.assertIn("start http://pi/c/ab/hls.m3u8 12.5", self.device.calls)
        first = self.said.of("status")[0]
        self.assertEqual("BUFFERING", first["state"])
        self.assertEqual("http://pi/c/ab/hls.m3u8", first["contentId"])

    def test_the_receivers_own_position_and_length_are_reported_while_it_plays(self):
        async def steps():
            await self.load()
            self.device.info_now = {"position": 7.5, "duration": 180.0, "rate": 1.0}
            await asyncio.sleep(0.05)

        self.run_async(steps)

        playing = [s for s in self.said.of("status") if s["state"] == "PLAYING"][-1]
        self.assertEqual(7500, playing["positionMs"])
        self.assertEqual(180000, playing["durationMs"])

    def test_a_rate_of_nothing_is_paused_and_no_length_is_still_buffering(self):
        async def steps():
            await self.load()
            self.device.info_now = {"position": 3.0, "duration": 60.0, "rate": 0.0}
            await asyncio.sleep(0.05)

        self.run_async(steps)

        self.assertEqual("PAUSED", self.said.of("status")[-1]["state"])

    def test_an_item_that_ends_by_itself_is_finished_for_that_item(self):
        async def steps():
            await self.load()
            self.device.started.set_result(None)
            await asyncio.sleep(0.05)

        self.run_async(steps)

        last = self.said.of("status")[-1]
        self.assertEqual(("IDLE", "FINISHED"), (last["state"], last["idle"]))
        self.assertEqual("http://pi/c/ab/hls.m3u8", last["contentId"])

    def test_a_refusal_for_want_of_pairing_names_the_command_that_fixes_it(self):
        async def steps():
            await self.load()
            self.device.started.set_exception(AuthenticationError("status code: 470"))
            await asyncio.sleep(0.05)

        self.run_async(steps)

        message = self.said.of("error")[0]["message"]
        self.assertIn("totum airplay-pair 192.168.0.219", message)
        last = self.said.of("status")[-1]
        self.assertEqual(("IDLE", "ERROR"), (last["state"], last["idle"]))

    def test_stopping_is_not_reported_as_an_item_that_finished(self):
        async def steps():
            await self.load()
            await self.player.command({"cmd": "stop"})
            await asyncio.sleep(0.05)

        self.run_async(steps)

        self.assertIn("halt", self.device.calls)
        self.assertEqual([], [s for s in self.said.of("status") if s["state"] == "IDLE"])

    def test_pause_resume_and_seek_are_the_receivers_rate_and_scrub(self):
        async def steps():
            await self.load()
            await self.player.command({"cmd": "pause"})
            await self.player.command({"cmd": "resume"})
            await self.player.command({"cmd": "seek", "positionMs": 61000})

        self.run_async(steps)

        self.assertEqual(["rate 0.0", "rate 1.0", "scrub 61.0"], [c for c in self.device.calls if c.startswith(("rate", "scrub"))])

    def test_a_volume_is_never_sent_to_the_receiver_and_the_reason_is_noted(self):
        async def steps():
            await self.load()
            await self.player.command({"cmd": "volume", "percent": 90})

        self.run_async(steps)

        self.assertEqual([], [c for c in self.device.calls if "volume" in c])
        self.assertTrue(any("volume 90%" in n["message"] for n in self.said.of("note")))

    def test_a_second_load_stops_the_first_and_the_stopped_one_does_not_end_the_new_item(self):
        async def steps():
            await self.load()
            first = self.device.started
            await self.player.command(
                {"cmd": "load", "url": "http://pi/c/cd/hls.m3u8", "type": "x", "title": "Next", "startMs": 0}
            )
            self.assertTrue(first.done())

        self.run_async(steps)

        self.assertEqual([], [s for s in self.said.of("status") if s["state"] == "IDLE"])
        self.assertEqual(2, len([c for c in self.device.calls if c.startswith("start ")]))


class CredentialsTest(unittest.TestCase):
    def test_credentials_are_stored_private_and_read_back_and_never_printed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "airplay", "airplay-lg-tv.json")
            out = io.StringIO()

            with mock.patch.object(sys, "stdout", out):
                airplay_helper.Credentials(path).save("SECRET-HAP-STRING", "0D:12", "LG TV")
                loaded = airplay_helper.Credentials(path).load()

            self.assertEqual("SECRET-HAP-STRING", loaded)
            self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))
            self.assertNotIn("SECRET", out.getvalue())

    def test_an_overwritten_file_keeps_its_private_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "c.json")
            open(path, "w").close()
            os.chmod(path, 0o644)

            airplay_helper.Credentials(path).save("x", "i", "n")

            self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))

    def test_no_file_is_no_credentials_and_a_broken_one_is_noted_not_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            out = io.StringIO()
            with mock.patch.object(sys, "stdout", out):
                self.assertIsNone(airplay_helper.Credentials(os.path.join(directory, "none.json")).load())
                broken = os.path.join(directory, "broken.json")
                with open(broken, "w") as handle:
                    handle.write("{not json")
                self.assertIsNone(airplay_helper.Credentials(broken).load())
                self.assertIsNone(airplay_helper.Credentials(None).load())

            self.assertIn("unreadable", out.getvalue())


if __name__ == "__main__":
    unittest.main()
