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


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now


class StepTimelineTest(unittest.TestCase):

    def test_each_step_runs_until_the_next_one_starts(self):
        clock = FakeClock()
        log = CollectingLogger(clock=clock)
        clock.now += 0.05
        log.debug(HEALTHY_TRANSCRIPT[1])
        clock.now += 0.4
        log.debug(HEALTHY_TRANSCRIPT[7])
        clock.now += 8.1

        self.assertEqual(
            "total 8550ms: start 50ms | webpage 400ms | [jsc:quickjs] Solving JS challenges using quickjs 8100ms",
            log.timeline(),
        )

    def test_routine_steps_still_make_no_notes(self):
        log = CollectingLogger(clock=FakeClock())
        for line in HEALTHY_TRANSCRIPT:
            log.debug(line)

        self.assertEqual([], log.notes())
        self.assertIn("android vr player API JSON", log.timeline())

    def test_a_long_run_of_steps_is_bounded_and_says_so(self):
        log = CollectingLogger(clock=FakeClock())
        for n in range(CollectingLogger.MAX_STEPS + 5):
            log.debug(f"[youtube] x: step {n}")

        self.assertIn("and 5 more steps", log.timeline())
        self.assertNotIn(f"step {CollectingLogger.MAX_STEPS} ", log.timeline())


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
            # The warnings yt-dlp emits BEFORE it gives up are the ones worth keeping. Tolerant of a
            # caller with no logger, so a missing one shows up as the bridge's own failure rather than
            # this stub's.
            logger = self.options.get("logger")
            if logger is not None:
                logger.warning(REAL_WARNING)
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
        self.assertIn("; total ", result["steps"])
        self.assertIn("; solver player cache ", result["steps"])

    def test_a_failed_extraction_says_where_its_time_went(self):
        module, _ = _bridge_with_stubbed_ytdlp()

        result = json.loads(module.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw"))

        self.assertFalse(result["ok"])
        self.assertIn("; total ", result["steps"])
        self.assertIn("; solver player cache ", result["steps"])

class SharedPlayerCacheTest(unittest.TestCase):

    class _FakeYdl:
        def __init__(self):
            self.extractor = type("Ie", (), {"_code_cache": {}, "_player_cache": {}})()

        def get_info_extractor(self, key):
            return self.extractor

    def test_two_extractions_share_one_player_cache(self):
        module, _ = _bridge_with_stubbed_ytdlp()
        first, second = self._FakeYdl(), self._FakeYdl()

        module._share_player_caches(first)
        first.extractor._code_cache["build-a"] = "player js"
        module._share_player_caches(second)

        self.assertEqual("player js", second.extractor._code_cache.get("build-a"))

    def test_only_the_newest_player_builds_are_kept(self):
        module, _ = _bridge_with_stubbed_ytdlp()
        for build in ("a", "b", "c", "d"):
            ydl = self._FakeYdl()
            module._share_player_caches(ydl)
            ydl.extractor._code_cache[build] = "js"
        module._share_player_caches(self._FakeYdl())

        self.assertEqual(["c", "d"], list(module._SHARED_PLAYER_CODE))

    def test_a_missing_extractor_degrades_rather_than_failing(self):
        module, _ = _bridge_with_stubbed_ytdlp()

        class Broken:
            def get_info_extractor(self, key):
                raise KeyError(key)

        self.assertFalse(module._share_player_caches(Broken()))


class SolverCacheFilesTest(unittest.TestCase):

    def _ydl(self, root):
        cache = type("Cache", (), {"_get_root_dir": lambda self: root})()
        return type("Ydl", (), {"cache": cache})()

    def test_names_each_cached_player_by_build_and_variant(self):
        import tempfile
        module, _ = _bridge_with_stubbed_ytdlp()
        with tempfile.TemporaryDirectory() as root:
            section = pathlib.Path(root) / "challenge-solver"
            section.mkdir()
            name = "player,3Ahttps,3A,2F,2Fwww.youtube.com,2Fs,2Fplayer,2F1b3be681,2Fplayer_ias.vflset,2Fen_US,2Fbase.js.json"
            (section / name).write_bytes(b"x" * 4096)

            self.assertEqual("[1b3be681/player_ias.vflset 4KB]", module._solver_cache_files(self._ydl(root)))

    def test_an_absent_cache_says_so(self):
        import tempfile
        module, _ = _bridge_with_stubbed_ytdlp()
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual("[no cache dir]", module._solver_cache_files(self._ydl(root)))


class FailedSearchTest(unittest.TestCase):
    """A failed search must come back as a failure, not crash the caller with a NameError (field report
    2026-09-28, 0.1.548: `NameError: name 'logger' is not defined` at totum_ytdlp.search)."""

    def test_a_failed_search_returns_a_failure_with_notes(self):
        module, _ = _bridge_with_stubbed_ytdlp()

        result = json.loads(module.search("jazz live stream", 5))

        self.assertFalse(result["ok"])
        self.assertTrue(any("SABR-only" in note for note in result["notes"]), result)


class FailedDownloadNotesTest(unittest.TestCase):
    """
    A failed DOWNLOAD reports what yt-dlp noticed too -- and this is the test that was missing.

    Adding notes to the failure envelope was done with a blanket string replace, and the same
    `except yt_dlp.utils.DownloadError` block appears in `download()` as well as `extract()` -- where
    there is no collecting logger in scope. So every failed download raised
    `NameError: name 'logger' is not defined` instead of returning a value, which broke the contract's
    one rule (expected failures are values, never exceptions).

    The extract-path test passed throughout, because it only ever exercised extract. The live
    instrumented suite caught it (SeekDeepIntoALongVideoTest, 2026-08-19) -- a JVM-and-python-only
    gate could not have. This test is the cheap guard that belongs underneath it.
    """

    class Listener:
        def onProgress(self, done, total, eta):
            pass

    def test_a_failed_download_returns_a_value_rather_than_raising(self):
        module, _ = _bridge_with_stubbed_ytdlp()

        result = json.loads(module.download(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "/tmp",
            None,
            self.Listener(),
            None,
            "",
        ))

        self.assertFalse(result["ok"])
        self.assertEqual("extractor", result["kind"])

    def test_a_failed_download_reports_what_yt_dlp_noticed(self):
        module, _ = _bridge_with_stubbed_ytdlp()

        result = json.loads(module.download(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "/tmp",
            None,
            self.Listener(),
            None,
            "",
        ))

        self.assertIn("notes", result, f"a failed download must say what preceded it: {result}")
        self.assertTrue(
            any("SABR-only" in note for note in result["notes"]),
            f"the warning yt-dlp emitted before giving up has to survive: {result}",
        )

class PoTokenPassThroughTest(unittest.TestCase):
    """
    A PO token reaches yt-dlp's extractor args, in the shape it documents.

    The attestation wall (docs/todos/youtube-requires-attestation.md) ends at one missing thing: a PO
    token. The bundled yt-dlp 2026.07.04 already accepts one -- `CLIENT.CONTEXT+PO_TOKEN`, contexts
    `player`, `gvs`, `subs` -- so the bridge does not need to know how a token is MINTED to be able to
    carry one. This is that seam, and it is the half that is needed whichever way minting is solved.

    Asserted against the options actually handed to YoutubeDL, because "we set an option" and "yt-dlp
    received it under the key it reads" are different claims, and only the second one plays a video.
    """

    def _options_seen(self, **kwargs):
        module, stub = _bridge_with_stubbed_ytdlp()
        seen = {}

        class Recording(stub.YoutubeDL):
            def __init__(self, options):
                seen.update(options)
                super().__init__(options)

            def extract_info(self, url, download=False):
                return {"id": "jNQXAC9IVRw"}

        stub.YoutubeDL = Recording
        module.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw", **kwargs)
        return seen

    def test_no_token_leaves_the_extractor_args_alone(self):
        args = self._options_seen()["extractor_args"]

        self.assertNotIn("po_token", args["youtube"], f"nothing to pass must add nothing: {args}")
        self.assertIn("player_client", args["youtube"], "the client list must survive: %s" % args)

    def test_a_token_arrives_under_the_key_yt_dlp_reads(self):
        args = self._options_seen(po_token=["web.gvs+TOKENVALUE"])["extractor_args"]

        self.assertEqual(["web.gvs+TOKENVALUE"], args["youtube"]["po_token"])
        self.assertIn("player_client", args["youtube"], "adding a token must not drop the client list")

    def test_a_download_carries_the_token_too(self):
        module, stub = _bridge_with_stubbed_ytdlp()
        seen = {}

        class Recording(stub.YoutubeDL):
            def __init__(self, options):
                seen.update(options)
                super().__init__(options)

            def extract_info(self, url, download=False):
                return {"id": "x", "requested_downloads": [{"filepath": "/tmp/x.webm"}]}

        stub.YoutubeDL = Recording

        class Listener:
            def onProgress(self, done, total, eta):
                pass

        module.download(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw", "/tmp", None, Listener(), None, "",
            po_token=["web.gvs+TOKENVALUE"],
        )

        self.assertEqual(
            ["web.gvs+TOKENVALUE"],
            seen["extractor_args"]["youtube"]["po_token"],
            "a download is refused by the same wall as a play, so it needs the same token",
        )

    def test_the_shared_client_list_is_not_mutated(self):
        self._options_seen(po_token=["web.gvs+TOKENVALUE"])
        module, _ = _bridge_with_stubbed_ytdlp()

        self.assertNotIn(
            "po_token",
            module.PLAYER_CLIENTS["youtube"],
            "a per-call token must not stick to the module-level dict every other call shares",
        )




PLAYABLE = {"id": "v", "formats": [{"url": "https://rr.test/videoplayback?n=x", "vcodec": "avc1", "acodec": "mp4a"}]}


class ClientFallbackTest(unittest.TestCase):

    def _run(self, answer_for, po_token=None):
        module, stub = _bridge_with_stubbed_ytdlp()
        seen = []

        class Scripted(stub.YoutubeDL):
            def extract_info(self, url, download=False):
                stub.current_logger = self.options["logger"]
                clients = self.options["extractor_args"]["youtube"]["player_client"]
                seen.append((list(clients), self.options["extractor_args"]["youtube"].get("po_token")))
                return answer_for(clients, stub)

        stub.YoutubeDL = Scripted
        result = json.loads(module.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw", po_token=po_token))
        return result, seen, module

    def test_web_embedded_alone_is_asked_first_and_is_enough_when_it_plays(self):
        result, seen, _ = self._run(lambda clients, stub: PLAYABLE)

        self.assertTrue(result["ok"])
        self.assertEqual([["web_embedded"]], [clients for clients, _ in seen])
        self.assertTrue(result["steps"].startswith("clients web_embedded;"), result["steps"])

    def test_a_failure_retries_with_every_client(self):
        def answer(clients, stub):
            if clients == ["web_embedded"]:
                raise stub.utils.DownloadError("This video is not available")
            return PLAYABLE

        result, seen, module = self._run(answer)

        self.assertTrue(result["ok"])
        self.assertEqual(module.PLAYER_CLIENTS["youtube"]["player_client"], seen[-1][0])
        self.assertIn("retried with every client", result["steps"])

    def test_nothing_playable_retries_with_every_client(self):
        def answer(clients, stub):
            return {"id": "v", "formats": [{"url": None, "vcodec": "avc1"}]} if clients == ["web_embedded"] else PLAYABLE

        result, seen, _ = self._run(answer)

        self.assertEqual(2, len(seen))
        self.assertEqual(PLAYABLE["formats"][0]["url"], result["info"]["formats"][0]["url"])

    def test_a_sabr_degraded_ladder_retries_with_every_client(self):
        def answer(clients, stub):
            if clients == ["web_embedded"]:
                stub.current_logger.warning(
                    "[youtube] -mv1Tf26Vms: Some web_embedded client https formats have been skipped as they are "
                    "missing a URL. YouTube may have enabled the SABR-only streaming experiment")
            return PLAYABLE

        result, seen, module = self._run(answer)

        self.assertEqual([["web_embedded"], module.PLAYER_CLIENTS["youtube"]["player_client"]], [c for c, _ in seen])
        self.assertIn("SABR-only", result["steps"])

    def test_when_both_fail_the_failure_says_both_were_tried(self):
        def answer(clients, stub):
            raise stub.utils.DownloadError("Private video")

        result, seen, _ = self._run(answer)

        self.assertFalse(result["ok"])
        self.assertEqual(2, len(seen))
        self.assertIn("retried with every client", result["steps"])

    def test_a_po_token_reaches_both_attempts(self):
        def answer(clients, stub):
            if clients == ["web_embedded"]:
                raise stub.utils.DownloadError("nope")
            return PLAYABLE

        _, seen, _ = self._run(answer, po_token=["web.gvs+TOKEN"])

        self.assertEqual([["web.gvs+TOKEN"], ["web.gvs+TOKEN"]], [token for _, token in seen])


class SolverPlayerCacheTest(unittest.TestCase):
    """The JS challenge solver keeps its preprocessed player between solves, and prunes old builds.

    Measured 2026-09-07 on the emulator: the same video's two resolves each spent ~15s in the solver,
    because yt-dlp ships the preprocessed-player cache OFF and hands QuickJS the 2.9MB player every time.
    """

    def test_the_preprocessed_player_cache_is_switched_on(self):
        import types

        ejs = types.ModuleType("yt_dlp.extractor.youtube.jsc._builtin.ejs")

        class EJSBaseJCP:
            _ENABLE_PREPROCESSED_PLAYER_CACHE = False

        ejs.EJSBaseJCP = EJSBaseJCP
        module, stub = _bridge_with_stubbed_ytdlp()
        saved = {k: sys.modules.get(k) for k in (
            "yt_dlp.extractor", "yt_dlp.extractor.youtube", "yt_dlp.extractor.youtube.jsc",
            "yt_dlp.extractor.youtube.jsc._builtin", "yt_dlp.extractor.youtube.jsc._builtin.ejs")}
        try:
            for k in saved:
                sys.modules[k] = ejs if k.endswith(".ejs") else types.ModuleType(k)
            self.assertTrue(module._enable_solver_player_cache())
            self.assertTrue(EJSBaseJCP._ENABLE_PREPROCESSED_PLAYER_CACHE)
        finally:
            for k, v in saved.items():
                if v is None:
                    sys.modules.pop(k, None)
                else:
                    sys.modules[k] = v

    def test_a_wheel_without_the_flag_degrades_to_slow_solves_not_a_crash(self):
        module, stub = _bridge_with_stubbed_ytdlp()
        sys.modules.pop("yt_dlp.extractor.youtube.jsc._builtin.ejs", None)
        self.assertIn(module._enable_solver_player_cache(), (True, False))

    def _section_with(self, root, builds):
        import os

        section = pathlib.Path(root) / "challenge-solver"
        section.mkdir()
        (section / "lib.json").write_text("{}")
        for age, build in enumerate(builds):
            path = section / f"player,3Ahttps,3A,2F,2Fwww.youtube.com,2Fs,2Fplayer,2F{build},2Fbase.js.json"
            path.write_text("{}")
            os.utime(path, (1_000_000 - age * 100, 1_000_000 - age * 100))
        return section

    def _extractor(self, root):
        cache = type("Cache", (), {"_get_root_dir": lambda self: root})()
        return type("Extractor", (), {"cache": cache})()

    def test_two_builds_in_use_at_once_are_both_kept(self):
        import tempfile

        module, _ = _bridge_with_stubbed_ytdlp()
        with tempfile.TemporaryDirectory() as root:
            section = self._section_with(root, ["1b3be681", "1f293754"])

            self.assertEqual(0, module._prune_solver_player_cache(self._extractor(root)))
            self.assertEqual(3, len(list(section.iterdir())))

    def test_only_the_oldest_players_beyond_three_are_dropped(self):
        import tempfile

        module, _ = _bridge_with_stubbed_ytdlp()
        with tempfile.TemporaryDirectory() as root:
            section = self._section_with(root, ["newest", "second", "third", "oldest"])

            self.assertEqual(1, module._prune_solver_player_cache(self._extractor(root)))
            names = {p.name for p in section.iterdir()}
            self.assertIn("lib.json", names)
            self.assertFalse(any("oldest" in n for n in names), names)
            self.assertTrue(all(any(b in n for n in names) for b in ("newest", "second", "third")), names)

if __name__ == "__main__":
    unittest.main(verbosity=2)
