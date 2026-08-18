#!/usr/bin/env python3
"""Tests for youtube-canary.py.

The canary's whole value is that it is believed, so the two ways it could quietly lie are pinned
here:

* **Reporting a change that did not happen.** A network hiccup on the Pi returns "unknown", and if
  that were recorded as a state it would fire a BROKEN alert on every blip — which is how a channel
  earns being muted, and then the one real alert lands in a muted channel.
* **Not reporting a change that did.** Silence is the normal case, so a bug that makes it always
  silent is invisible. The transition in both directions is asserted.

Run: python3 tools/ci/youtube_canary_test.py
"""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_spec = importlib.util.spec_from_file_location("youtube_canary", Path(__file__).with_name("youtube-canary.py"))
canary = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(canary)


class VerdictTest(unittest.TestCase):
    """How a status code becomes a verdict — the judgement, without the network."""

    def _verdict(self, url, status):
        with mock.patch.object(canary, "audio_url", return_value=url), \
             mock.patch.object(canary, "deep_fetch_status", return_value=status):
            return canary.verdict()[0]

    def test_a_partial_content_response_is_working(self):
        self.assertEqual("working", self._verdict("https://x.test/a", 206))

    def test_a_plain_ok_is_working_too(self):
        """Some hosts answer 200 to a range request. The bytes arrived either way."""
        self.assertEqual("working", self._verdict("https://x.test/a", 200))

    def test_a_forbidden_deep_range_is_broken(self):
        """THE case: 403 deep into the file is the 2026-08-18 signature exactly."""
        self.assertEqual("broken", self._verdict("https://x.test/a", 403))

    def test_no_url_is_unknown_rather_than_broken(self):
        """We measured nothing. Calling that "broken" would blame YouTube for our own yt-dlp."""
        self.assertEqual("unknown", self._verdict(None, None))

    def test_an_unmakeable_request_is_unknown(self):
        self.assertEqual("unknown", self._verdict("https://x.test/a", None))


class ReportingTest(unittest.TestCase):
    """When it speaks and when it stays quiet."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name) / "state"

    def _run(self, said, extra=()):
        with mock.patch.object(canary, "verdict", return_value=(said, "because")), \
             mock.patch.object(canary, "notify") as notify:
            canary.main(["--state", str(self.state), *extra])
        return notify

    def test_the_first_broken_verdict_is_announced(self):
        notify = self._run("broken")

        self.assertEqual(1, notify.call_count)
        self.assertIn("BROKEN", notify.call_args[0][1])
        self.assertEqual("broken", self.state.read_text())

    def test_staying_broken_is_silent(self):
        self.state.write_text("broken")

        self.assertEqual(0, self._run("broken").call_count)

    def test_recovery_is_announced(self):
        """Good news is news: it is the signal to stop working around the breakage."""
        self.state.write_text("broken")

        notify = self._run("working")

        self.assertIn("RECOVERED", notify.call_args[0][1])
        self.assertEqual("working", self.state.read_text())

    def test_unknown_never_counts_as_a_change(self):
        self.state.write_text("working")

        notify = self._run("unknown")

        self.assertEqual(0, notify.call_count)
        self.assertEqual("working", self.state.read_text(), "an unknown must not overwrite the state")


if __name__ == "__main__":
    unittest.main(verbosity=2)
