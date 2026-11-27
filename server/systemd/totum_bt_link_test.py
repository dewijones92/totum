import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("totum-bt-link.sh")


class BtLinkTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="bt-link-"))
        self.out = self.dir / "out"
        self.fake = self.dir / "hcitool"
        self.fake.write_text(
            "#!/bin/sh\n"
            'case "$1" in\n'
            "  con) printf 'Connections:\\n\\t< ACL F4:4E:FD:21:92:6D handle 12 state 1 lm MASTER AUTH ENCRYPT\\n' ;;\n"
            "  rssi) printf 'RSSI return value: -13\\n' ;;\n"
            "  lq) printf 'Link quality: 255\\n' ;;\n"
            "  tpl) printf 'Current transmit power level: 12\\n' ;;\n"
            "esac\n"
        )
        self.fake.chmod(self.fake.stat().st_mode | stat.S_IEXEC)

    def run_once(self, hci=None):
        env = {
            **os.environ,
            "TOTUM_BT_LINK_DIR": str(self.out),
            "TOTUM_BT_LINK_ONCE": "1",
            "TOTUM_HCITOOL": str(hci or self.fake),
        }
        return subprocess.run(["sh", str(SCRIPT)], env=env, capture_output=True, text=True)

    def test_a_connected_device_gets_one_file_with_its_numbers(self):
        done = self.run_once()

        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        text = (self.out / "F4_4E_FD_21_92_6D.txt").read_text().strip()
        fields = dict(part.split("=") for part in text.split())
        self.assertEqual("-13", fields["rssi"])
        self.assertEqual("255", fields["lq"])
        self.assertEqual("12", fields["tx"])
        self.assertTrue(int(fields["at"]) > 1_700_000_000)

    def test_no_connected_device_writes_nothing_and_does_not_fail(self):
        silent = self.dir / "silent"
        silent.write_text("#!/bin/sh\nexit 0\n")
        silent.chmod(silent.stat().st_mode | stat.S_IEXEC)

        done = self.run_once(silent)

        self.assertEqual(0, done.returncode)
        self.assertEqual([], list(self.out.glob("*.txt")))


if __name__ == "__main__":
    unittest.main()
