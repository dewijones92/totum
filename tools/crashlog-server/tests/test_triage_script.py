import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "triage.sh"

FAKE_SSH = """#!/usr/bin/env bash
shift
printf '%s\\n' "$*" >> "$FAKE_SSH_LOG"
case "$*" in
  docker*) echo 10.0.0.9 ;;
  *) cat "$FAKE_SSH_REPLY" ;;
esac
"""


def report(report_id, considered):
    return {
        "id": report_id,
        "kind": "diagnostics",
        "app_version": "0.1.555",
        "state": "triaged" if considered else "new",
        "considered": considered,
        "has_state": False,
        "screenshots": 0,
        "has_note": False,
        "findings": [],
    }


@unittest.skipUnless(shutil.which("bash") and shutil.which("python3"), "needs bash and python3")
class TriageConsideredTest(unittest.TestCase):
    def run_considered(self, reply, *args):
        work = Path(tempfile.mkdtemp())
        ssh = work / "ssh"
        ssh.write_text(FAKE_SSH)
        ssh.chmod(ssh.stat().st_mode | stat.S_IEXEC)
        (work / "reply.json").write_text(json.dumps(reply))
        env = dict(
            os.environ,
            PATH=f"{work}:{os.environ['PATH']}",
            FAKE_SSH_LOG=str(work / "log"),
            FAKE_SSH_REPLY=str(work / "reply.json"),
        )
        result = subprocess.run(
            ["bash", str(SCRIPT), "considered", *args], env=env, capture_output=True, text=True, check=True
        )
        return result.stdout, (work / "log").read_text()

    def test_with_no_date_it_asks_for_every_unconsidered_report(self):
        reply = {"reports": [report("a", False)], "unconsidered": 1, "unconsidered_total": 1, "unconsidered_hidden": 0}

        _, asked = self.run_considered(reply)

        self.assertIn("unconsidered=all", asked)

    def test_an_explicit_date_is_the_window_and_does_not_ask_for_more(self):
        reply = {"reports": [], "unconsidered": 0, "unconsidered_total": 0, "unconsidered_hidden": 0}

        _, asked = self.run_considered(reply, "2026-10-01")

        self.assertIn("since=2026-10-01", asked)
        self.assertNotIn("unconsidered=all", asked)

    def test_the_footer_states_the_server_wide_count(self):
        reply = {
            "reports": [report("a", False), report("b", True)],
            "unconsidered": 1,
            "unconsidered_total": 20,
            "unconsidered_hidden": 0,
        }

        out, _ = self.run_considered(reply)

        self.assertIn("20 unconsidered server-wide", out)
        self.assertNotIn("OUTSIDE this window", out)

    def test_a_window_that_hides_an_unconsidered_report_says_so_loudly(self):
        reply = {
            "reports": [report("a", False)],
            "unconsidered": 1,
            "unconsidered_total": 20,
            "unconsidered_hidden": 19,
        }

        out, _ = self.run_considered(reply, "2026-11-01")

        self.assertIn("19 UNCONSIDERED report(s) are OUTSIDE this window", out)

    def test_a_server_that_predates_the_total_is_called_out(self):
        reply = {"reports": [report("a", False)], "unconsidered": 1}

        out, _ = self.run_considered(reply)

        self.assertIn("PREDATES unconsidered_total", out)


if __name__ == "__main__":
    unittest.main()
