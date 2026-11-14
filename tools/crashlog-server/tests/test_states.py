import gzip
import json
import os
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


class LinkedStatesTest(unittest.TestCase):
    def setUp(self):
        self.states = Path(tempfile.mkdtemp()) / "states"
        self.start = datetime(2026, 11, 14, 9, 0, 0, tzinfo=timezone.utc)

    def link(self, report: str, body: bytes | None = None):
        return states.save_linked(self.states, report, body if body is not None else dump())

    def test_a_linked_dump_is_stored_whole_under_its_report_id_and_apart_from_the_unlinked(self):
        body = dump("0.1.930")

        saved, created = self.link("20261114T090000-0a1b2c3d", body)

        self.assertTrue(created)
        self.assertEqual("20261114T090000-0a1b2c3d.json.gz", saved.name)
        self.assertEqual(body, saved.read_bytes())
        self.assertEqual(self.states / "linked", saved.parent)
        self.assertEqual([], list(self.states.glob("*.json.gz")))

    def test_a_second_dump_for_the_same_report_changes_nothing(self):
        first = dump("0.1.1")
        self.link("20261114T090000-0a1b2c3d", first)

        saved, created = self.link("20261114T090000-0a1b2c3d", dump("0.1.2"))

        self.assertFalse(created)
        self.assertEqual(first, saved.read_bytes())

    def test_the_unlinked_newest_five_never_touch_a_linked_dump(self):
        saved, _ = self.link("20261114T090000-0a1b2c3d")
        for minute in range(9):
            states.save(self.states, dump(), self.start + timedelta(minutes=minute), 5)

        self.assertTrue(saved.exists())
        self.assertEqual(5, len(list(self.states.glob("*.json.gz"))))

    def test_a_report_id_that_is_not_the_servers_own_shape_is_refused_before_anything_is_written(self):
        for bad in ("../x", "r-fixed", "20261114T090000-0A1B2C3D", "20261114T090000-0a1b2c3d/x", ""):
            with self.assertRaises(states.Refused, msg=bad):
                self.link(bad)
        self.assertFalse(self.states.exists())

    def test_a_linked_upload_is_checked_like_any_other_dump(self):
        with self.assertRaises(states.Refused):
            self.link("20261114T090000-0a1b2c3d", b"not gzip")
        self.assertEqual([], list(states.linked_directory(self.states).glob("*")))

    def test_releasing_deletes_the_dump_and_says_how_big_it_was(self):
        body = dump()
        self.link("20261114T090000-0a1b2c3d", body)

        freed = states.release(self.states, "20261114T090000-0a1b2c3d")

        self.assertEqual(len(body), freed)
        self.assertIsNone(states.linked_path(self.states, "20261114T090000-0a1b2c3d"))
        self.assertIsNone(states.release(self.states, "20261114T090000-0a1b2c3d"))

    def test_the_budget_drops_the_oldest_first_and_keeps_the_newest_even_when_it_alone_is_over(self):
        ids = [f"20261114T09000{n}-0a1b2c3d" for n in range(3)]
        paths = []
        for n, report in enumerate(ids):
            saved, _ = self.link(report, dump(extra={"pad": os.urandom(400).hex()}))
            os.utime(saved, (1000 + n, 1000 + n))
            paths.append(saved)
        newest_two = paths[1].stat().st_size + paths[2].stat().st_size

        dropped = states.enforce_budget(self.states, newest_two)
        self.assertEqual([paths[0].name], [p.name for p in dropped])

        dropped = states.enforce_budget(self.states, 1)
        self.assertEqual([paths[1].name], [p.name for p in dropped])
        self.assertTrue(paths[2].exists())

    def test_the_listing_names_the_report_of_a_linked_dump_and_the_latest_is_the_newest_of_both_kinds(self):
        states.save(self.states, dump("0.1.old"), self.start)
        saved, _ = self.link("20261114T090000-0a1b2c3d", dump("0.1.new"))
        os.utime(saved, (self.start.timestamp() + 60, self.start.timestamp() + 60))

        rows = states.listing(self.states)

        self.assertEqual(["20261114T090000-0a1b2c3d", None], [r["report"] for r in rows])
        self.assertEqual(saved, states.latest(self.states))


if __name__ == "__main__":
    unittest.main()
