#!/usr/bin/env python3
import importlib.util
import json
import os
import pathlib
import sys
import tempfile
import unittest

BRIDGE = pathlib.Path(__file__).resolve().parents[2] / "main/python/totum_ytdlp.py"

try:
    import yt_dlp  # noqa: F401
    from yt_dlp.extractor.youtube.jsc.provider import JsChallengeRequest, JsChallengeType, NChallengeInput
except ImportError:
    if os.environ.get("CI"):
        raise
    print("yt-dlp is not installed for this python; skipping the v8 provider tests", file=sys.stderr)
    sys.exit(0)

PLAYER_URL = "https://www.youtube.com/s/player/1f293754/player_ias.vflset/en_US/base.js"


def _bridge():
    spec = importlib.util.spec_from_file_location("totum_ytdlp", BRIDGE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BRIDGE_MODULE = _bridge()


class FakeRuntime:
    def __init__(self):
        self.up = True
        self.library = None
        self.kept = {}
        self.calls = []
        self.fail_with = None

    def available(self):
        return self.up

    def hasLibrary(self, key):
        return self.library == key

    def loadLibrary(self, key, code):
        self.calls.append("loadLibrary")
        self.library = key

    def keepPlayer(self, key, player_json):
        self.calls.append(f"keepPlayer {json.loads(player_json)}")
        self.kept[key] = json.loads(player_json)
        return True

    def _answer(self, base, requests_json):
        if self.fail_with:
            raise self.fail_with
        requests = json.loads(requests_json)
        return {"type": "result", "responses": [
            {"type": "result", "data": {c: f"{c}<-{base}" for c in r["challenges"]}} for r in requests]}

    def solveKept(self, key, requests_json):
        self.calls.append("solveKept")
        if key not in self.kept:
            return None
        return json.dumps(self._answer(self.kept[key], requests_json))

    def solveWithPlayer(self, key, player_json, preprocessed, requests_json):
        player = json.loads(player_json)
        self.calls.append(f"solveWithPlayer {'preprocessed' if preprocessed else 'raw'} {player}")
        out = self._answer(player, requests_json)
        if not preprocessed:
            out["preprocessed_player"] = f"PRE({player})"
        self.kept[key] = player if preprocessed else out["preprocessed_player"]
        return json.dumps(out)


class Notes:
    def __init__(self):
        self.lines = []

    def debug(self, message):
        self.lines.append(message)

    info = warning = error = debug


class V8ProviderTest(unittest.TestCase):

    def setUp(self):
        self.runtime = FakeRuntime()
        self.assertEqual("v8 solver registered", BRIDGE_MODULE.configure_v8_solver(self.runtime))
        BRIDGE_MODULE._enable_solver_player_cache()
        BRIDGE_MODULE._V8_PROVIDER._get_player = lambda provider, video_id, url: "RAWPLAYER"
        self.cache = tempfile.TemporaryDirectory()
        self.notes = Notes()
        self.ydl = yt_dlp.YoutubeDL({"quiet": True, "logger": self.notes, "cachedir": self.cache.name, "js_runtimes": {}})
        self.ie = self.ydl.get_info_extractor("Youtube")
        self.ie.initialize()

    def tearDown(self):
        self.ydl.close()
        self.cache.cleanup()

    def _solve(self, *challenges):
        request = JsChallengeRequest(JsChallengeType.N, NChallengeInput(PLAYER_URL, list(challenges)), "vid")
        return {k: v for _, response in self.ie._jsc_director.bulk_solve([request])
                for k, v in response.output.results.items()}

    def test_registering_twice_is_harmless(self):
        self.assertEqual("v8 solver registered", BRIDGE_MODULE.configure_v8_solver(self.runtime))

    def test_v8_is_asked_before_quickjs(self):
        director = self.ie._jsc_director
        score = {name: sum(pref(provider, []) for pref in director.preferences)
                 for name, provider in director.providers.items()}

        self.assertGreater(score["AndroidV8"], score["QuickJS"], score)

    def test_a_new_build_is_solved_in_full_and_its_preprocessed_player_cached(self):
        self.assertEqual({"a": "a<-RAWPLAYER"}, self._solve("a"))

        self.assertEqual(["solveKept", "loadLibrary", "solveWithPlayer raw RAWPLAYER"], self.runtime.calls)
        self.assertEqual("PRE(RAWPLAYER)", self.ie.cache.load("challenge-solver", f"player:{PLAYER_URL}"))

    def test_the_next_solve_comes_from_memory(self):
        self._solve("a")
        self.runtime.calls.clear()

        self.assertEqual({"b": "b<-PRE(RAWPLAYER)"}, self._solve("b"))
        self.assertEqual(["solveKept"], self.runtime.calls)

    def test_after_a_restart_the_cached_player_is_used_rather_than_the_raw_one(self):
        self.ie.cache.store("challenge-solver", f"player:{PLAYER_URL}", "PRE(cached)")

        self.assertEqual({"a": "a<-PRE(cached)"}, self._solve("a"))
        self.assertIn("solveWithPlayer preprocessed PRE(cached)", self.runtime.calls)

    def test_a_v8_failure_is_reported_and_never_raised(self):
        self.runtime.fail_with = RuntimeError("SandboxDeadException: gone")

        self.assertEqual({}, self._solve("a"))
        self.assertTrue(any("v8 failed" in line and "gone" in line for line in self.notes.lines), self.notes.lines)

    def test_an_unavailable_runtime_is_skipped(self):
        self.runtime.up = False

        self.assertNotIn("AndroidV8", [p.PROVIDER_NAME for p in self.ie._jsc_director._get_providers([])])

    def test_the_steps_line_says_whether_v8_is_on(self):
        self.assertIn("v8 on", BRIDGE_MODULE._solver_cache_state())
        self.runtime.up = False
        self.assertIn("v8 off", BRIDGE_MODULE._solver_cache_state())

    def test_warm_up_loads_the_library_and_the_newest_cached_players(self):
        self.ie.cache.store("challenge-solver", f"player:{PLAYER_URL}", "PRE(cached)")

        result = BRIDGE_MODULE._preload_v8(self.ydl)

        self.assertIn("library loaded, 1 cached players kept", result)
        self.assertEqual(["loadLibrary", "keepPlayer PRE(cached)"], self.runtime.calls)
        self.assertEqual({"a": "a<-PRE(cached)"}, self._solve("a"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
