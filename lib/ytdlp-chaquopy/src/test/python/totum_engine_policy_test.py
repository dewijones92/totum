#!/usr/bin/env python3
import gzip
import http.server
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

HERE = pathlib.Path(__file__).resolve()
BRIDGE = HERE.parents[2] / "main/python/totum_ytdlp.py"
PLAYER_FIXTURE = HERE.parents[4] / "ytdlp/src/testFixtures/resources/player-1f293754.js.gz"
NODE = shutil.which("node")

try:
    import yt_dlp  # noqa: F401
except ImportError:
    if os.environ.get("CI"):
        raise
    print("yt-dlp is not installed for this python; skipping the engine policy tests", file=sys.stderr)
    sys.exit(0)

if NODE is None:
    if os.environ.get("CI"):
        raise RuntimeError("node is needed to solve the fixture player")
    print("node is not installed; skipping the engine policy tests", file=sys.stderr)
    sys.exit(0)

PLAYER_PATH = "/s/player/1f293754/player_ias.vflset/en_US/base.js"


class PlayerServer:
    def __init__(self):
        self.requests = 0
        body = gzip.decompress(PLAYER_FIXTURE.read_bytes())
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                outer.requests += 1
                self.send_response(200)
                self.send_header("Content-Type", "application/javascript")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}{PLAYER_PATH}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def _load_bridge():
    spec = importlib.util.spec_from_file_location("totum_ytdlp_policy", BRIDGE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.configure_js_runtime(NODE, "node")
    return module


class EnginePolicyTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.cache = tempfile.TemporaryDirectory()
        os.environ["XDG_CACHE_HOME"] = cls.cache.name
        cls.server = PlayerServer()
        cls.bridge = _load_bridge()

    @classmethod
    def tearDownClass(cls):
        cls.server.close()
        cls.cache.cleanup()

    def state(self):
        return json.loads(self.bridge.engine_state())

    def test_a_in_the_order_a_device_meets_them(self):
        before = self.state()
        self.assertFalse(before["warm"])

        warmed = json.loads(self.bridge._main(["warm"]))
        self.assertTrue(warmed["ok"], warmed)

        ready = self.state()
        self.assertTrue(ready["warm"])
        self.assertTrue(ready["solver_ready"])
        self.assertTrue(ready["solver_player_cache"])
        self.assertTrue(ready["shared_player_caches"])
        self.assertTrue(ready["network_shared"])

        prepared = json.loads(self.bridge.prepare_player(self.server.url))
        self.assertTrue(prepared["ok"], prepared)
        self.assertFalse(prepared["already"])
        self.assertEqual(1, prepared["fetches"])
        self.assertEqual(1, prepared["parses"])
        self.assertEqual(1, self.server.requests)
        self.assertIn(self.server.url, self.state()["preprocessed_players"])

        again = json.loads(self.bridge.prepare_player(self.server.url))
        self.assertTrue(again["already"], again)

        parses = self.state()["player_parses"]
        fetches = self.state()["player_fetches"]
        solved = json.loads(self.bridge.solve_n(["Ab1cdEfGhIjKlMn-"], self.server.url))
        self.assertTrue(solved["ok"], solved)
        self.assertIn("Ab1cdEfGhIjKlMn-", solved["solved"])
        self.assertEqual(parses, self.state()["player_parses"], "a solve for a prepared build parsed the player again")
        self.assertEqual(fetches, self.state()["player_fetches"], "a solve for a prepared build fetched the player again")
        self.assertEqual(1, self.server.requests, "a solve for a prepared build went to the network")

    def test_b_a_worker_answers_the_same_commands_and_keeps_its_state(self):
        env = dict(os.environ, TOTUM_JS_RUNTIME=f"node:{NODE}")
        worker = subprocess.Popen(
            [sys.executable, str(BRIDGE), "serve"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env=env,
        )
        try:
            def ask(*args):
                worker.stdin.write(json.dumps({"id": len(args), "args": list(args)}) + "\n")
                worker.stdin.flush()
                reply = json.loads(worker.stdout.readline())
                self.assertEqual(len(args), reply["id"])
                return json.loads(reply["output"])

            self.assertTrue(ask("warm")["ok"])
            first = ask("solve_n", json.dumps(["Zy9xwVuTsRqPoNm_"]), self.server.url)
            self.assertTrue(first["ok"], first)
            state = ask("state")
            self.assertTrue(state["warm"])
            self.assertEqual(1, state["solves"])
            self.assertEqual(0, state["player_parses"], "the preprocessed copy from the earlier test should have served it")
            self.assertFalse(ask("nonsense")["ok"])
        finally:
            worker.stdin.close()
            worker.wait(timeout=20)


if __name__ == "__main__":
    unittest.main()
