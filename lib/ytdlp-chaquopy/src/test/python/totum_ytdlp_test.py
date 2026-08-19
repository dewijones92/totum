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
import importlib.util
import json
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


def _bridge_with_stubbed_ytdlp():
    """
    Imports the real bridge against a stub yt-dlp, so the JSON envelope itself can be tested.

    Possible because the module imports only `json`, `platform` and `yt_dlp` at the top level --
    no Chaquopy `java` -- so the whole thing loads on a plain interpreter. Returns the module and
    the stub, and the stub's error class is the one the module will catch: raising an instance of
    any OTHER class with the same name sails straight through the `except`.
    """
    import types

    class DownloadError(Exception):
        def __init__(self, message, exc_info=None):
            super().__init__(message)
            self.exc_info = exc_info

    class UnsupportedError(DownloadError):
        pass

    stub = types.ModuleType("yt_dlp")

    class YoutubeDL:
        def __init__(self, options):
            self.options = options

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def extract_info(self, url, download=False):
            # The warnings yt-dlp emits BEFORE it gives up are the ones worth keeping.
            self.options["logger"].warning(REAL_WARNING)
            raise stub.failWith

        def sanitize_info(self, info):
            return info

    stub.version = types.SimpleNamespace(__version__="stub")
    stub.YoutubeDL = YoutubeDL
    stub.utils = types.SimpleNamespace(
        DownloadError=DownloadError,
        UnsupportedError=UnsupportedError,
        network_exceptions=(OSError,),
    )
    stub.failWith = DownloadError("nothing playable")
    sys.modules["yt_dlp"] = stub
    spec = importlib.util.spec_from_file_location("totum_ytdlp_under_test", BRIDGE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, stub


class FailedExtractionNotesTest(unittest.TestCase):
    """
    A FAILED extraction is when yt-dlp's own warnings matter most -- and they were thrown away.

    The success envelope carried `notes`; the `except DownloadError` branch returned only `kind` and
    `detail`, so the sentence explaining WHY it failed ("...formats have been skipped as they are
    missing a URL. YouTube may have enabled the SABR-only streaming experiment...") was collected and
    then dropped. A report of a video that would not play could say what went wrong but not what
    yt-dlp had noticed on the way there.
    """

    def test_a_failed_extraction_still_reports_what_yt_dlp_noticed(self):
        module, _ = _bridge_with_stubbed_ytdlp()

        result = json.loads(module.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw"))

        self.assertFalse(result["ok"])
        self.assertIn("notes", result, f"a failure must carry the warnings that preceded it: {result}")
        self.assertTrue(
            any("SABR-only" in note for note in result["notes"]),
            f"the warning yt-dlp emitted before giving up has to survive: {result}",
        )

    def test_a_successful_extraction_is_unchanged(self):
        module, stub = _bridge_with_stubbed_ytdlp()
        stub.YoutubeDL.extract_info = lambda self, url, download=False: {"id": "jNQXAC9IVRw"}

        result = json.loads(module.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw"))

        self.assertTrue(result["ok"])
        self.assertEqual("jNQXAC9IVRw", result["info"]["id"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
