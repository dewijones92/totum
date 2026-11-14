import contextlib
import gzip
import io
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("CRASHLOG_DATA", tempfile.mkdtemp())
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))

try:
    from fastapi.testclient import TestClient
    import main
    import states
except ImportError:  # the web framework and httpx are in the server image, not on every laptop
    main = None

HEADER = "X-Totum-Report-Id"


def dump(version: str = "0.1.900", pad: int = 0) -> bytes:
    body = {"format": 1, "kind": "totum-state-dump", "appVersion": version, "installId": "abcd1234"}
    if pad:
        body["pad"] = uuid.uuid4().hex * pad
    return gzip.compress(json.dumps(body).encode())


def new_report_id() -> str:
    return f"{datetime.now(timezone.utc):%Y%m%dT%H%M%S}-{uuid.uuid4().hex[:8]}"


def add_report(report_id: str | None = None) -> str:
    report_id = report_id or new_report_id()
    path = Path(main.REPORTS_DIR) / f"{report_id}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("{}")
    with sqlite3.connect(main.DB_PATH) as connection:
        connection.execute(
            "INSERT INTO reports (id, received_at, path, kind, app_version) VALUES (?,?,?,?,?)",
            (report_id, datetime.now(timezone.utc).isoformat(), str(path), "diagnostics", "0.1.900"),
        )
    return report_id


def add_finding(report_id: str, title: str, state: str, fixed_in: str | None = None):
    return main.api_add_finding(report_id, title=title, state=state, fixed_in=fixed_in, note=None)


@unittest.skipIf(main is None, "needs fastapi and httpx")
class ReportStatesTest(unittest.TestCase):
    def setUp(self):
        shutil.rmtree(main.STATES_DIR, ignore_errors=True)
        self.client = TestClient(main.app)

    def send(self, report_id: str | None, body: bytes | None = None, via_query: bool = False):
        body = dump() if body is None else body
        if report_id is None:
            return self.client.post("/ingest/state", content=body)
        if via_query:
            return self.client.post("/ingest/state", params={"report": report_id}, content=body)
        return self.client.post("/ingest/state", content=body, headers={HEADER: report_id})

    def logged(self, action) -> str:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            action()
        return out.getvalue()

    def considered_row(self, report_id: str) -> dict:
        reports = self.client.get("/api/considered", params={"since": "2000-01-01"}).json()["reports"]
        return next(r for r in reports if r["id"] == report_id)

    def test_a_dump_is_linked_to_its_report_and_fetched_by_the_report_id(self):
        report = add_report()
        body = dump("0.1.911")

        posted = self.send(report, body)

        self.assertEqual(202, posted.status_code)
        self.assertEqual(report, posted.json()["report"])
        fetched = self.client.get(f"/api/report/{report}/state")
        self.assertEqual(200, fetched.status_code)
        self.assertEqual(body, fetched.content)
        self.assertTrue(self.considered_row(report)["has_state"])
        self.assertEqual(len(body), self.considered_row(report)["state_bytes"])

    def test_the_report_id_may_come_as_a_query_argument_too(self):
        report = add_report()

        self.assertEqual(202, self.send(report, via_query=True).status_code)

        self.assertEqual(200, self.client.get(f"/api/report/{report}/state").status_code)

    def test_a_report_with_no_dump_says_so_and_its_row_has_no_mark(self):
        report = add_report()

        self.assertFalse(self.considered_row(report)["has_state"])
        missing = self.client.get(f"/api/report/{report}/state")
        self.assertEqual(404, missing.status_code)
        self.assertFalse(missing.json()["considered"])
        self.assertEqual(404, self.client.get(f"/api/report/{new_report_id()}/state").status_code)

    def test_a_dump_is_kept_while_its_report_is_unconsidered_however_many_others_arrive(self):
        report = add_report()
        self.send(report)
        add_finding(report, "still new", "new")
        for _ in range(8):
            self.send(None)
            self.send(add_report())

        self.assertEqual(200, self.client.get(f"/api/report/{report}/state").status_code)
        self.assertEqual(5, len([r for r in self.client.get("/api/states").json() if r["report"] is None]))

    def test_a_dump_is_deleted_the_moment_its_report_has_a_verdict_and_the_log_says_which_and_why(self):
        report = add_report()
        self.send(report)

        said = self.logged(lambda: add_finding(report, "no summons", "fixed", "v1"))

        self.assertEqual(404, self.client.get(f"/api/report/{report}/state").status_code)
        self.assertFalse(self.considered_row(report)["has_state"])
        self.assertIn(f"deleted linked dump {report}.json.gz", said)
        self.assertIn("considered", said)

    def test_judging_the_last_open_finding_or_the_whole_report_also_releases_the_dump(self):
        first = add_report()
        self.send(first)
        open_finding = json.loads(add_finding(first, "a", "new").body)
        self.assertEqual(200, self.client.get(f"/api/report/{first}/state").status_code)
        main.api_update_finding(open_finding["id"], state="noise", title=None, fixed_in=None, note=None)
        self.assertEqual(404, self.client.get(f"/api/report/{first}/state").status_code)

        whole = add_report()
        self.send(whole)
        main.api_triage(whole, state="wontfix", fixed_in=None, note="whole report")
        self.assertEqual(404, self.client.get(f"/api/report/{whole}/state").status_code)

    def test_judging_every_report_of_one_exception_releases_all_their_dumps(self):
        reports = [add_report() for _ in range(2)]
        with sqlite3.connect(main.DB_PATH) as connection:
            for report in reports:
                connection.execute("UPDATE reports SET exception = 'bulk.Boom' WHERE id = ?", (report,))
        for report in reports:
            self.send(report)

        main.api_triage_signature(exception="bulk.Boom", state="fixed", fixed_in="v2", note=None)

        for report in reports:
            self.assertEqual(404, self.client.get(f"/api/report/{report}/state").status_code)

    def test_a_judged_report_in_the_middle_does_not_take_a_neighbours_dump(self):
        judged, waiting = add_report(), add_report()
        self.send(judged)
        self.send(waiting)

        main.api_triage(judged, state="fixed", fixed_in="v1", note=None)

        self.assertEqual(404, self.client.get(f"/api/report/{judged}/state").status_code)
        self.assertEqual(200, self.client.get(f"/api/report/{waiting}/state").status_code)

    def test_a_dump_for_a_report_that_is_already_considered_is_not_kept(self):
        report = add_report()
        main.api_triage(report, state="noise", fixed_in=None, note=None)

        said = self.logged(lambda: self.assertEqual(202, self.send(report).status_code))

        self.assertEqual(404, self.client.get(f"/api/report/{report}/state").status_code)
        self.assertIn("already considered", said)

    def test_the_same_dump_sent_twice_is_kept_once_and_the_retry_is_a_success(self):
        report = add_report()
        first = dump("0.1.1")

        self.send(report, first)
        again = self.send(report, dump("0.1.2"))

        self.assertEqual(200, again.status_code)
        self.assertTrue(again.json()["duplicate"])
        self.assertEqual(first, self.client.get(f"/api/report/{report}/state").content)
        self.assertEqual(1, len(list(states.linked_directory(main.STATES_DIR).glob("*.json.gz"))))

    def test_a_dump_for_a_report_the_server_never_had_is_refused_with_the_reason_logged(self):
        said = self.logged(lambda: self.assertEqual(404, self.send(new_report_id()).status_code))

        self.assertIn("no such report", said)
        self.assertEqual([], self.client.get("/api/states").json())

    def test_a_malformed_report_id_is_refused_and_cannot_name_a_path(self):
        for bad in ("../../etc/passwd", "r-fixed", "20261114T120000-ZZZZZZZZ", "a/b"):
            self.assertEqual(400, self.send(bad).status_code, bad)
        self.assertEqual([], self.client.get("/api/states").json())

    def test_a_linked_upload_that_is_not_a_dump_is_a_400_and_stores_nothing(self):
        report = add_report()

        refused = self.send(report, b"not gzip")

        self.assertEqual(400, refused.status_code)
        self.assertEqual(404, self.client.get(f"/api/report/{report}/state").status_code)

    def test_an_old_phone_uploading_with_no_report_id_keeps_the_newest_five_as_before(self):
        for n in range(8):
            self.assertEqual(202, self.send(None, dump(f"0.1.{n}")).status_code)

        unlinked = [r for r in self.client.get("/api/states").json() if r["report"] is None]

        self.assertEqual(5, len(unlinked))
        self.assertEqual("0.1.7", unlinked[0]["appVersion"])
        self.assertEqual(200, self.client.get("/state/latest").status_code)

    def test_an_old_unlinked_dump_already_on_disk_is_listed_and_never_mistaken_for_a_report_s(self):
        states.save(main.STATES_DIR, dump("0.1.old"), datetime.now(timezone.utc))
        report = add_report()
        self.send(report, dump("0.1.new"))

        listed = self.client.get("/api/states").json()

        self.assertEqual({"0.1.old": None, "0.1.900": report}, {r["appVersion"]: r["report"] for r in listed})
        self.assertEqual(200, self.client.get("/state/latest").status_code)

    def test_the_newest_dump_of_either_kind_is_the_latest(self):
        states.save(main.STATES_DIR, dump("0.1.old"), datetime.now(timezone.utc))
        report = add_report()
        newest = dump("0.1.linked")
        self.send(report, newest)

        self.assertEqual(newest, self.client.get("/state/latest").content)

    def test_past_the_size_budget_the_oldest_unconsidered_dump_is_dropped_loudly(self):
        old, middle, newest = add_report(), add_report(), add_report()
        bodies = {report: dump(pad=400) for report in (old, middle, newest)}
        budget = main.STATE_BUDGET_BYTES
        main.STATE_BUDGET_BYTES = sum(len(b) for b in bodies.values()) - 1
        try:
            self.send(old, bodies[old])
            self.send(middle, bodies[middle])
            said = self.logged(lambda: self.send(newest, bodies[newest]))
        finally:
            main.STATE_BUDGET_BYTES = budget

        self.assertEqual(404, self.client.get(f"/api/report/{old}/state").status_code)
        self.assertEqual(200, self.client.get(f"/api/report/{middle}/state").status_code)
        self.assertEqual(200, self.client.get(f"/api/report/{newest}/state").status_code)
        self.assertIn("BUDGET EXCEEDED", said)
        self.assertIn(f"{old}.json.gz", said)
        self.assertIn("still unconsidered", said)

    def test_the_budget_never_drops_the_dump_that_has_just_arrived(self):
        report = add_report()
        budget = main.STATE_BUDGET_BYTES
        main.STATE_BUDGET_BYTES = 1
        try:
            self.send(report)
        finally:
            main.STATE_BUDGET_BYTES = budget

        self.assertEqual(200, self.client.get(f"/api/report/{report}/state").status_code)

    def test_a_dump_whose_report_has_been_pruned_is_swept_away(self):
        report = add_report()
        self.send(report)
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute("DELETE FROM reports WHERE id = ?", (report,))

        said = self.logged(lambda: main._sweep_states("test"))

        self.assertIsNone(states.linked_path(main.STATES_DIR, report))
        self.assertIn("no longer exists", said)

    def test_a_sweep_at_start_up_releases_a_dump_whose_report_was_judged_while_the_server_was_down(self):
        report = add_report()
        self.send(report)
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute("UPDATE reports SET state = 'fixed' WHERE id = ?", (report,))

        main._sweep_states("startup")

        self.assertIsNone(states.linked_path(main.STATES_DIR, report))

    def test_a_report_posted_to_ingest_gets_an_id_a_dump_can_then_be_linked_to(self):
        posted = self.client.post("/ingest", content=json.dumps({"kind": "diagnostics", "message": "m"}))
        report = posted.json()["id"]

        self.assertEqual(202, self.send(report).status_code)
        self.assertTrue(self.considered_row(report)["has_state"])


if __name__ == "__main__":
    unittest.main()
