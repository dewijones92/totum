#!/usr/bin/env python3
import importlib.util
import os
import pathlib
import sys
import unittest

BRIDGE = pathlib.Path(__file__).resolve().parents[2] / "main/python/totum_ytdlp.py"

try:
    import yt_dlp
except ImportError:
    if os.environ.get("CI"):
        raise
    print("yt-dlp is not installed for this python; skipping the shared network tests", file=sys.stderr)
    sys.exit(0)


def _cookie(name, value):
    import http.cookiejar

    return http.cookiejar.Cookie(0, name, value, None, False, ".youtube.com", True, True, "/", True,
                                 True, None, False, None, None, {})


def _bridge():
    spec = importlib.util.spec_from_file_location("totum_ytdlp", BRIDGE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BRIDGE_MODULE = _bridge()


class SharedNetworkTest(unittest.TestCase):

    def _extraction(self):
        ydl = yt_dlp.YoutubeDL({"quiet": True})
        self.assertTrue(BRIDGE_MODULE._lend_network(ydl))
        return ydl

    def _finish(self, ydl):
        BRIDGE_MODULE._return_network(ydl)
        ydl.close()

    def test_every_extraction_uses_the_same_connections_and_cookies(self):
        first, second = self._extraction(), self._extraction()

        self.assertIs(first._request_director, second._request_director)
        self.assertIs(first.cookiejar, second.cookiejar)
        self._finish(first)
        self._finish(second)

    def test_closing_an_extraction_keeps_the_pooled_session_open(self):
        first = self._extraction()
        handler = first._request_director.handlers.get("Requests") or first._request_director.handlers["Urllib"]
        session = handler._get_instance(cookiejar=first.cookiejar, **self._extra(handler))

        self._finish(first)

        second = self._extraction()
        self.assertIs(session, handler._get_instance(cookiejar=second.cookiejar, **self._extra(handler)))
        self._finish(second)

    def test_each_burst_of_extractions_starts_with_fresh_cookies(self):
        first = self._extraction()
        first.cookiejar.set_cookie(_cookie("VISITOR_INFO1_LIVE", "sticky"))
        overlapping = self._extraction()
        self.assertEqual(["VISITOR_INFO1_LIVE"], [c.name for c in overlapping.cookiejar])
        self._finish(first)
        self._finish(overlapping)

        later = self._extraction()

        self.assertEqual([], [c.name for c in later.cookiejar])
        self._finish(later)

    def test_the_steps_line_says_which_handlers_are_shared(self):
        self._finish(self._extraction())

        self.assertTrue(BRIDGE_MODULE._network_state().startswith("http shared ["), BRIDGE_MODULE._network_state())

    @staticmethod
    def _extra(handler):
        return {"legacy_ssl_support": None} if handler.RH_KEY == "Requests" else {"proxies": {}}


if __name__ == "__main__":
    unittest.main(verbosity=2)
