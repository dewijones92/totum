import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("totum-tv-arp.sh")


class TvArpTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="tv-arp-"))
        self.calls = self.dir / "calls.txt"
        self.fake_ip = self.dir / "ip"
        self.fake_ip.write_text(
            "#!/bin/sh\n"
            'if [ "$1" = "-4" ] && [ "$2" = "route" ]; then\n'
            '  [ -n "$FAKE_NO_ROUTE" ] && exit 1\n'
            '  echo "$4 via 192.168.0.1 dev ${FAKE_DEV:-wlan0} src 192.168.0.19"\n'
            "  exit 0\n"
            "fi\n"
            f'echo "$@" >> "{self.calls}"\n'
            '[ -n "$FAKE_FAIL" ] && exit 2\n'
            "exit 0\n"
        )
        self.fake_ip.chmod(self.fake_ip.stat().st_mode | stat.S_IEXEC)

    def run_script(self, env_text, **extra):
        env_file = self.dir / "fetch.env"
        if env_text is not None:
            env_file.write_text(env_text)
        env = {
            **os.environ,
            "TOTUM_FETCH_ENV": str(env_file),
            "TOTUM_IP": str(self.fake_ip),
            **extra,
        }
        done = subprocess.run(["sh", str(SCRIPT)], env=env, capture_output=True, text=True)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return done, calls

    def test_pins_every_target_that_has_a_mac_and_skips_the_rest(self):
        done, calls = self.run_script(
            "TOTUM_X=1\nTOTUM_CAST_TARGETS=LG TV=192.168.0.219@20:28:bc:6b:f7:f8;"
            "Chromecast Ultra=192.168.0.31;Kitchen=192.168.0.40@AA:BB:CC:DD:EE:FF\nTOTUM_Y=2\n"
        )

        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertEqual(
            [
                "neigh replace 192.168.0.219 lladdr 20:28:bc:6b:f7:f8 nud permanent dev wlan0",
                "neigh replace 192.168.0.40 lladdr AA:BB:CC:DD:EE:FF nud permanent dev wlan0",
            ],
            calls,
        )
        self.assertIn("skipped 'Chromecast Ultra=192.168.0.31'", done.stdout)
        self.assertIn("2 pinned, 1 skipped, 0 failed", done.stdout)

    def test_falls_back_to_eth0_when_no_route_is_known(self):
        done, calls = self.run_script(
            "TOTUM_CAST_TARGETS=LG TV=192.168.0.219@20:28:bc:6b:f7:f8\n", FAKE_NO_ROUTE="1"
        )

        self.assertEqual(0, done.returncode, done.stdout)
        self.assertEqual(["neigh replace 192.168.0.219 lladdr 20:28:bc:6b:f7:f8 nud permanent dev eth0"], calls)
        self.assertIn("no route found to 192.168.0.219, using eth0", done.stdout)

    def test_a_bad_mac_or_address_is_skipped_and_named(self):
        done, calls = self.run_script(
            "TOTUM_CAST_TARGETS=A=192.168.0.1@zz:zz;B=tv.local@20:28:bc:6b:f7:f8\n"
        )

        self.assertEqual(0, done.returncode)
        self.assertEqual([], calls)
        self.assertIn("'zz:zz' is not a MAC", done.stdout)
        self.assertIn("'tv.local' is not an IPv4 address", done.stdout)

    def test_a_failing_ip_command_fails_the_unit_and_says_which(self):
        done, _ = self.run_script("TOTUM_CAST_TARGETS=LG TV=192.168.0.219@20:28:bc:6b:f7:f8\n", FAKE_FAIL="1")

        self.assertNotEqual(0, done.returncode)
        self.assertIn("FAILED to pin 192.168.0.219", done.stdout)

    def test_an_unreadable_env_file_fails_loudly(self):
        done, calls = self.run_script(None)

        self.assertEqual(1, done.returncode)
        self.assertEqual([], calls)
        self.assertIn("cannot read", done.stdout)

    def test_no_targets_is_not_an_error(self):
        done, calls = self.run_script("TOTUM_OTHER=1\n")

        self.assertEqual(0, done.returncode)
        self.assertEqual([], calls)
        self.assertIn("TOTUM_CAST_TARGETS is not set", done.stdout)

    def test_quoted_value_is_read(self):
        done, calls = self.run_script('TOTUM_CAST_TARGETS="LG TV=192.168.0.219@20:28:bc:6b:f7:f8"\n')

        self.assertEqual(0, done.returncode, done.stdout)
        self.assertEqual(1, len(calls))


if __name__ == "__main__":
    unittest.main()
