import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

DATA = tempfile.mkdtemp()
os.environ["CRASHLOG_DATA"] = DATA
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))

import main  # noqa: E402


def body(response):
    return json.loads(response.body)


def add_report(report_id, received_at="2026-10-02T05:41:26+00:00"):
    path = Path(DATA) / "reports" / f"{report_id}.json"
    path.write_text("{}")
    with sqlite3.connect(main.DB_PATH) as connection:
        connection.execute(
            "INSERT INTO reports (id, received_at, path, kind, app_version) VALUES (?,?,?,?,?)",
            (report_id, received_at, str(path), "diagnostics", "0.1.555"),
        )
    return report_id


def add(report_id, title, state="new", fixed_in=None, note=None):
    return main.api_add_finding(report_id, title=title, state=state, fixed_in=fixed_in, note=note)


def update(finding_id, state, title=None, fixed_in=None, note=None):
    return main.api_update_finding(finding_id, state=state, title=title, fixed_in=fixed_in, note=note)


def report_state(report_id):
    with sqlite3.connect(main.DB_PATH) as connection:
        return connection.execute(
            "SELECT state, fixed_in FROM reports WHERE id = ?", (report_id,)
        ).fetchone()


class FindingsTest(unittest.TestCase):
    def test_a_report_is_considered_only_once_every_finding_is_judged(self):
        report = add_report("r-partial")
        add(report, title="no summons", state="fixed", fixed_in="v0.1.556")
        open_one = body(add(report, title="old crash in logcat"))

        self.assertFalse(body(main.api_report_findings(report))["considered"])
        self.assertEqual("new", report_state(report)[0])

        update(open_one["id"], state="noise")

        self.assertTrue(body(main.api_report_findings(report))["considered"])
        self.assertEqual("triaged", report_state(report)[0])

    def test_one_shared_verdict_becomes_the_reports_with_its_version(self):
        report = add_report("r-fixed")
        add(report, title="a", state="fixed", fixed_in="v0.1.556")
        add(report, title="b", state="fixed", fixed_in="v0.1.556")

        self.assertEqual(("fixed", "v0.1.556"), report_state(report))

    def test_an_update_keeps_what_it_was_not_given(self):
        report = add_report("r-keep")
        finding = body(add(report, title="t", state="triaged", note="why"))

        updated = body(update(finding["id"], state="fixed", fixed_in="v1"))

        self.assertEqual(("t", "why", "v1"), (updated["title"], updated["note"], updated["fixed_in"]))

    def test_a_report_triaged_before_findings_existed_still_counts(self):
        report = add_report("r-legacy")
        main.api_triage(report, state="fixed", fixed_in="v0.1.551", note="whole report")

        self.assertTrue(body(main.api_report_findings(report))["considered"])

    def test_bad_input_is_refused(self):
        report = add_report("r-bad")
        self.assertEqual(400, add(report, title="t", state="maybe").status_code)
        self.assertEqual(404, add("no-such", title="t", state="new").status_code)
        self.assertEqual(404, update(99999, state="fixed").status_code)

    def test_the_considered_list_counts_what_is_left(self):
        add_report("r-old", received_at="2026-09-01T00:00:00+00:00")
        fresh = add_report("r-recent", received_at="2026-10-05T00:00:00+00:00")

        listed = body(main.api_considered(since="2026-10-05"))

        self.assertEqual([fresh], [r["id"] for r in listed["reports"]])
        self.assertEqual(1, listed["unconsidered"])

    def test_the_window_alone_hides_an_old_unconsidered_report_and_says_so(self):
        before = body(main.api_considered(since="2020-06-01", unconsidered=""))
        old = add_report("w-old-open", received_at="2020-01-01T00:00:00+00:00")

        listed = body(main.api_considered(since="2020-06-01", unconsidered=""))

        self.assertNotIn(old, [r["id"] for r in listed["reports"]])
        self.assertEqual(before["unconsidered_total"] + 1, listed["unconsidered_total"])
        self.assertEqual(before["unconsidered_hidden"] + 1, listed["unconsidered_hidden"])

    def test_asking_for_every_unconsidered_report_lists_the_old_ones_too(self):
        old_open = add_report("w-all-open", received_at="2020-01-02T00:00:00+00:00")
        old_done = add_report("w-all-done", received_at="2020-01-03T00:00:00+00:00")
        add(old_done, title="judged", state="noise")
        recent_done = add_report("w-all-recent-done", received_at="2020-07-01T00:00:00+00:00")
        add(recent_done, title="judged", state="noise")

        listed = body(main.api_considered(since="2020-06-01", unconsidered="all"))
        ids = [r["id"] for r in listed["reports"]]

        self.assertIn(old_open, ids)
        self.assertNotIn(old_done, ids)
        self.assertIn(recent_done, ids)
        self.assertEqual(0, listed["unconsidered_hidden"])
        self.assertEqual(listed["unconsidered_total"], sum(not r["considered"] for r in listed["reports"]))

    def test_the_server_wide_count_does_not_depend_on_the_window(self):
        narrow = body(main.api_considered(since="2999-01-01", unconsidered=""))
        wide = body(main.api_considered(since="", unconsidered=""))

        self.assertEqual(wide["unconsidered_total"], narrow["unconsidered_total"])
        self.assertEqual(0, narrow["unconsidered"])
        self.assertEqual(narrow["unconsidered_total"], narrow["unconsidered_hidden"])

    def test_pruning_a_report_takes_its_findings_with_it(self):
        report = add_report("r-pruned")
        add(report, title="t")
        path = Path(DATA) / "reports" / f"{report}.json"
        path.write_bytes(b"x" * 2048)
        limit = main.MAX_TOTAL_MB
        main.MAX_TOTAL_MB = 0
        try:
            main._prune()
        finally:
            main.MAX_TOTAL_MB = limit
        with sqlite3.connect(main.DB_PATH) as connection:
            left = connection.execute("SELECT COUNT(*) FROM findings WHERE report_id = ?", (report,)).fetchone()[0]
        self.assertEqual(0, left)


if __name__ == "__main__":
    unittest.main()
