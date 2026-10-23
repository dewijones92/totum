import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

DATA = tempfile.mkdtemp()
os.environ["CRASHLOG_DATA"] = DATA
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))

import main  # noqa: E402


class ScreenTest(unittest.TestCase):
    def setUp(self):
        if main.SCREEN_FILE.exists():
            main.SCREEN_FILE.unlink()

    def test_a_home_address_is_kept_and_the_bookmark_sends_the_browser_there(self):
        url = "http://192.168.0.42:8765/screen#key=abc12345"

        self.assertTrue(main.remember_screen({"kind": "screen", "url": url}))

        self.assertEqual(url, main.screen_target())

    def test_an_address_outside_home_and_wireguard_is_refused(self):
        self.assertFalse(main.remember_screen({"kind": "screen", "url": "http://203.0.113.9:8765/screen#key=x"}))
        self.assertFalse(main.remember_screen({"kind": "screen", "url": "https://evil.example/screen"}))
        self.assertIn("No phone yet", main.screen_page())

    def test_script_in_a_planted_address_never_reaches_the_page(self):
        planted = "http://192.168.0.1/</script><script>fetch('/api/reports')</script>"

        self.assertFalse(main.remember_screen({"kind": "screen", "url": planted}))
        self.assertNotIn("<script>fetch", main.screen_page())

    def test_only_the_exact_shape_the_phone_sends_is_kept(self):
        for wrong in (
            "http://192.168.0.1/router/reboot",
            "http://192.168.0.1:8765/screen#key=abc&x=<b>",
            "http://192.168.0.1:8765/screen?key=abc",
            "http://192.168.0.1:8765/screen#key=",
        ):
            self.assertFalse(main.remember_screen({"kind": "screen", "url": wrong}), wrong)

    def test_the_bookmark_redirects_by_header_not_by_script(self):
        url = "http://192.168.0.42:8765/screen#key=abc12345"
        main.remember_screen({"kind": "screen", "url": url})

        self.assertEqual(url, main.screen_target())
        self.assertNotIn("<script", main.screen_page())

    def test_wireguard_addresses_are_accepted(self):
        self.assertTrue(main.remember_screen({"kind": "screen", "url": "http://10.13.14.5:8765/screen#key=k1x2y3z4"}))


if __name__ == "__main__":
    unittest.main()
