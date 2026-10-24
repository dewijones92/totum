import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(__file__))
import server  # noqa: E402


class Clock:
    def __init__(self):
        self.now = 1_000_000.0

    def __call__(self):
        return self.now


class DevicesAndGateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "devices.json")
        self.clock = Clock()
        self.devices = server.Devices(self.path, clock=self.clock, days=30)
        self.retired = False
        self.gate = server.Gate(self.devices, "LEGACY", lambda: self.retired, clock=self.clock)

    def test_a_signed_in_device_passes_and_a_stranger_does_not(self):
        token, device = self.devices.mint("Pixel 7")
        self.assertEqual(("device", device["id"]), (self.gate.check(token, "/ts/x")[0], device["id"]))
        self.assertEqual("refused", self.gate.check("nope", "/ts/x")[0])
        self.assertEqual("refused", self.gate.check("", "/ts/x")[0])

    def test_a_device_token_in_a_link_is_refused(self):
        token, _ = self.devices.mint("Pixel 7")
        self.assertEqual("refused", self.gate.check("", f"/ts/stream/a.mkv?play&totumToken={token}")[0])

    def test_the_old_shared_token_works_in_header_or_link_until_retired(self):
        self.assertEqual("legacy", self.gate.check("LEGACY", "/x")[0])
        self.assertEqual("legacy", self.gate.check("", "/ts/stream/a.mkv?play&totumToken=LEGACY")[0])
        self.retired = True
        self.assertEqual("refused", self.gate.check("LEGACY", "/x")[0])
        self.assertEqual("refused", self.gate.check("", "/ts/stream/a.mkv?totumToken=LEGACY")[0])

    def test_signing_in_again_on_the_same_device_retires_its_old_token(self):
        old, _ = self.devices.mint("Pixel 7")
        new, _ = self.devices.mint("pixel 7")
        self.assertIsNone(self.devices.find(old))
        self.assertIsNotNone(self.devices.find(new))
        self.assertEqual(1, len(self.devices.listing()))

    def test_a_device_unused_for_the_whole_window_is_forgotten_and_use_keeps_it(self):
        kept, _ = self.devices.mint("used")
        idle, _ = self.devices.mint("idle")
        for _ in range(4):
            self.clock.now += 10 * server.DAY_S
            self.assertIsNotNone(self.devices.find(kept))
        self.assertIsNone(self.devices.find(idle))
        self.assertEqual(["used"], [d["name"] for d in self.devices.listing()])

    def test_a_revoke_from_another_process_is_seen_at_once(self):
        token, device = self.devices.mint("lost phone")
        other = server.Devices(self.path, clock=self.clock, days=30)
        os.utime(self.path, ns=(1, 1))
        self.assertTrue(other.revoke(device["id"]))
        self.assertIsNone(self.devices.find(token))

    def test_only_a_hash_of_each_token_is_stored(self):
        token, _ = self.devices.mint("Pixel 7")
        with open(self.path) as f:
            stored = f.read()
        self.assertNotIn(token, stored)
        self.assertIn(server.digest(token), stored)
        self.assertEqual(0o600, os.stat(self.path).st_mode & 0o777)


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.devices = server.Devices(os.path.join(self.dir, "devices.json"))
        gate = server.Gate(self.devices, "", lambda: True)
        handler = server.handler_for(gate, self.devices, "GATE", "PROWLARR")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def tearDown(self):
        self.httpd.shutdown()

    def call(self, path, method="GET", headers=None):
        request = urllib.request.Request(self.base + path, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(request) as reply:
                return reply.status, reply.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, ""

    def sign_in(self, name="Pixel 7"):
        code, page = self.call(f"/signin?device={urllib.parse.quote(name)}", headers={"X-Totum-Signin-Gate": "GATE"})
        self.assertEqual(200, code)
        return page.split("token=")[1].split("&")[0]

    def test_signing_in_needs_the_gated_host(self):
        self.assertEqual(403, self.call("/signin?device=x")[0])
        self.assertEqual(403, self.call("/signin?device=x", headers={"X-Totum-Signin-Gate": "guess"})[0])

    def test_a_signed_in_device_passes_the_check_lists_devices_and_can_sign_out(self):
        token = self.sign_in()
        self.assertEqual(204, self.call("/check", headers={"X-Totum-Token": token})[0])
        self.assertEqual(401, self.call("/check")[0])
        code, body = self.call("/auth/devices", headers={"X-Totum-Token": token})
        self.assertEqual((200, ["Pixel 7"]), (code, [d["name"] for d in json.loads(body)]))
        self.assertEqual(204, self.call("/auth/signout", "POST", {"X-Totum-Token": token})[0])
        self.assertEqual(401, self.call("/check", headers={"X-Totum-Token": token})[0])

    def test_the_sign_in_page_hands_over_the_token_and_the_prowlarr_key(self):
        _, page = self.call("/signin?device=Pixel%207", headers={"X-Totum-Signin-Gate": "GATE"})
        self.assertIn("totum://auth?token=", page)
        self.assertIn("key=PROWLARR", page)
        self.assertIn("Pixel 7", page)


if __name__ == "__main__":
    unittest.main()
