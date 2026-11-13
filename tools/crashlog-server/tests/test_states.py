import gzip
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))

import states  # noqa: E402


def dump(version: str = "0.1.900", install: str = "abcd1234-0000", extra: dict | None = None) -> bytes:
    body = {"format": 1, "kind": "totum-state-dump", "appVersion": version, "installId": install, "takenAtMs": 1}
    body.update(extra or {})
    return gzip.compress(json.dumps(body).encode())


class StatesTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.states = self.root / "states"
        self.reports = self.root / "reports"
        self.reports.mkdir()
        (self.reports / "keep-me.json").write_text("{}")
        self.start = datetime(2026, 11, 13, 18, 0, 0, tzinfo=timezone.utc)

    def save(self, minutes: int, body: bytes | None = None, keep: int = 5):
        return states.save(self.states, body if body is not None else dump(), self.start + timedelta(minutes=minutes), keep)

    def test_a_dump_is_stored_whole_and_named_for_when_which_phone_and_which_build(self):
        body = dump(version="0.1.912", install="3f2a9c1b-aaaa")

        saved = self.save(0, body)

        self.assertEqual(body, (self.states / saved.name).read_bytes())
        self.assertTrue(saved.name.startswith("20261113T180000"))
        self.assertIn("3f2a9c1b", saved.name)
        self.assertIn("0.1.912", saved.name)
        self.assertTrue(saved.name.endswith(".json.gz"))

    def test_only_the_latest_five_survive_and_the_oldest_go_first(self):
        saved = [self.save(minute) for minute in range(8)]

        kept = sorted(p.name for p in self.states.glob("*.json.gz"))

        self.assertEqual(5, len(kept))
        self.assertEqual(sorted(s.name for s in saved[3:]), kept)

    def test_the_number_kept_can_be_changed(self):
        for minute in range(6):
            self.save(minute, keep=2)

        self.assertEqual(2, len(list(self.states.glob("*.json.gz"))))

    def test_reports_are_never_touched_by_the_state_pruning(self):
        for minute in range(9):
            self.save(minute)

        self.assertEqual(["keep-me.json"], [p.name for p in self.reports.iterdir()])

    def test_the_listing_is_newest_first(self):
        for minute in range(3):
            self.save(minute, dump(version=f"0.1.{minute}"))

        listed = states.listing(self.states)

        self.assertEqual(["0.1.2", "0.1.1", "0.1.0"], [row["appVersion"] for row in listed])
        self.assertTrue(all(row["bytes"] > 0 for row in listed))

    def test_the_latest_is_the_newest_file(self):
        for minute in range(3):
            self.save(minute, dump(version=f"0.1.{minute}"))

        latest = states.latest(self.states)

        self.assertIsNotNone(latest)
        self.assertIn("0.1.2", latest.name)

    def test_nothing_stored_means_no_latest_and_an_empty_listing(self):
        self.assertIsNone(states.latest(self.states))
        self.assertEqual([], states.listing(self.states))

    def test_something_that_is_not_gzip_is_refused_and_not_stored(self):
        with self.assertRaises(states.Refused) as caught:
            self.save(0, b"this is not gzip")

        self.assertIn("gzip", str(caught.exception))
        self.assertEqual([], list(self.states.glob("*")))

    def test_gzip_that_is_not_a_state_dump_is_refused(self):
        for wrong in (b"[1,2]", b'{"hello":1}', b"not json at all", b'{"kind":"report"}'):
            with self.assertRaises(states.Refused, msg=wrong):
                self.save(0, gzip.compress(wrong))
        self.assertEqual([], list(self.states.glob("*.json.gz")))

    def test_a_dump_too_big_once_unpacked_is_refused(self):
        bomb = gzip.compress(json.dumps({"kind": "totum-state-dump", "pad": "a" * 5000}).encode())

        with self.assertRaises(states.Refused):
            states.save(self.states, bomb, self.start, 5, max_unpacked=1000)

    def test_a_dump_too_big_to_accept_is_refused(self):
        with self.assertRaises(states.Refused):
            states.save(self.states, dump(), self.start, 5, max_bytes=10)

    def test_a_name_cannot_climb_out_of_the_directory(self):
        saved = self.save(0, dump(version="../../etc/passwd", install="../../../root"))

        self.assertEqual(self.states.resolve(), saved.resolve().parent)
        self.assertNotIn("/", saved.name)
        self.assertNotIn("..", saved.name.replace(".json.gz", "").replace("0.1", ""))


if __name__ == "__main__":
    unittest.main()
