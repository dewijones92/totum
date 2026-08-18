#!/usr/bin/env python3
"""Tests for the yt-dlp bridge's note collector.

Run: python3 lib/ytdlp-chaquopy/src/test/python/totum_ytdlp_test.py

The collector exists to explain a DEGRADED extraction, and its first version did the opposite: it kept
everything yt-dlp routes through `debug()` without a "[debug] " prefix, on the belief that the prefix
marks routine output. It does not. In yt-dlp 2026.07.04 `YoutubeDL.to_screen` starts

    if self.params.get('logger'): self.params['logger'].debug(message); return

so every routine progress line arrives here unprefixed, while `write_debug` returns early unless
`verbose` is set — which extraction never sets. So the filter excluded nothing, a healthy extraction
produced nine notes, and the app logged a ~1KB WARN on EVERY resolve. That floods a bounded report buffer
and makes a healthy extraction indistinguishable from a degraded one, on the one line meant to tell them
apart.

These tests are the guard, and they need no Android, no Chaquopy and no network — the collector is plain
Python. The transcripts below are the real messages observed from yt-dlp 2026.07.04 against
`jNQXAC9IVRw`.
"""
import pathlib
import sys
import unittest

BRIDGE = pathlib.Path(__file__).resolve().parents[2] / "main/python/totum_ytdlp.py"


def _collector_class():
    """Loads just the collector, so importing does not require Chaquopy's `java` module."""
    source = BRIDGE.read_text()
    start = source.index("class _CollectingLogger")
    end = source.index("\ndef ", start)
    namespace: dict = {}
    exec(compile(source[start:end], str(BRIDGE), "exec"), namespace)  # noqa: S102
    return namespace["_CollectingLogger"]


CollectingLogger = _collector_class()

HEALTHY_TRANSCRIPT = [
    "[youtube] Extracting URL: https://www.youtube.com/watch?v=jNQXAC9IVRw",
    "[youtube] jNQXAC9IVRw: Downloading webpage",
    "[youtube] jNQXAC9IVRw: Downloading android vr player API JSON",
    "[youtube] jNQXAC9IVRw: Downloading android player API JSON",
    "[youtube] jNQXAC9IVRw: Downloading web embedded client config",
    "[youtube] jNQXAC9IVRw: Downloading player c74cbcd6-main",
    "[youtube] jNQXAC9IVRw: Downloading web embedded player API JSON",
    "[youtube] [jsc:quickjs] Solving JS challenges using quickjs",
]

REAL_WARNING = (
    "[youtube] jNQXAC9IVRw: Some android client https formats have been skipped as they are missing "
    "a URL. YouTube may have enabled the SABR-only streaming experiment for the current session."
)


class NoteCollectionTest(unittest.TestCase):
    """What reaches a diagnostics report, and what must not."""

    def test_a_healthy_extraction_produces_no_notes(self):
        """THE case. Every one of these arrives via debug(), unprefixed, on a perfectly good resolve."""
        log = CollectingLogger()
        for line in HEALTHY_TRANSCRIPT:
            log.debug(line)

        self.assertEqual([], log.notes())

    def test_a_real_warning_survives_the_chatter_around_it(self):
        log = CollectingLogger()
        log.debug(HEALTHY_TRANSCRIPT[1])
        log.warning(REAL_WARNING)
        log.debug(HEALTHY_TRANSCRIPT[5])

        notes = log.notes()
        self.assertEqual(1, len(notes), f"only the warning should survive: {notes}")
        self.assertIn("SABR-only", notes[0])
        self.assertTrue(notes[0].startswith("warning:"))

    def test_errors_are_kept(self):
        log = CollectingLogger()
        log.error("Unable to extract yt initial data")

        self.assertEqual(1, len(log.notes()))
        self.assertTrue(log.notes()[0].startswith("error:"))

    def test_a_truncated_note_list_says_how_many_were_dropped(self):
        """A silent truncation reads as "that was everything", which is the worse failure."""
        log = CollectingLogger()
        for n in range(CollectingLogger.MAX_KEPT + 8):
            log.warning(f"warning number {n}")

        notes = log.notes()
        self.assertEqual(CollectingLogger.MAX_KEPT + 1, len(notes))
        self.assertIn("8 more not kept", notes[-1])

    def test_an_untruncated_list_has_no_trailer(self):
        log = CollectingLogger()
        log.warning(REAL_WARNING)

        self.assertNotIn("not kept", " ".join(log.notes()))

    def test_a_long_message_is_capped_rather_than_dropped(self):
        log = CollectingLogger()
        log.warning("x" * 5000)

        self.assertEqual(1, len(log.notes()))
        self.assertLess(len(log.notes()[0]), 400)


if __name__ == "__main__":
    unittest.main(verbosity=2)
