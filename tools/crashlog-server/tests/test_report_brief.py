import base64
import json
import os
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
    import attachments
except ImportError:  # the web framework and httpx are in the server image, not on every laptop
    main = None

JPEG_ONE = b"\xff\xd8\xff-one-\xff\xd9"
JPEG_TWO = b"\xff\xd8\xff-two-two-\xff\xd9"


def shot(name: str, data: bytes) -> dict:
    return {"name": name, "width": 90, "height": 160, "bytes": len(data), "base64": base64.b64encode(data).decode()}


def report_body(note: str | None = None, screenshots: list | None = None) -> str:
    body = {"kind": "diagnostics", "appVersion": "0.1.950", "device": "Google Pixel 7", "android": "16 (API 36)"}
    if note is not None:
        body["note"] = note
    if screenshots is not None:
        body["screenshots"] = screenshots
    return json.dumps(body)


@unittest.skipIf(main is None, "needs fastapi and httpx")
class ReportBriefTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)

    def post(self, body: str) -> str:
        return self.client.post("/ingest", content=body).json()["id"]

    def row(self, report_id: str) -> dict:
        reports = self.client.get("/api/considered", params={"since": "2000-01-01"}).json()["reports"]
        return next(r for r in reports if r["id"] == report_id)

    def test_a_report_with_a_note_and_two_screenshots_says_so_in_the_considered_list(self):
        report = self.post(report_body("the queue jumped", [shot("screenshot-1.jpg", JPEG_ONE), shot("screenshot-2.jpg", JPEG_TWO)]))

        row = self.row(report)

        self.assertEqual(2, row["screenshots"])
        self.assertTrue(row["has_note"])

    def test_a_report_with_neither_says_so_too(self):
        report = self.post(report_body())

        row = self.row(report)

        self.assertEqual(0, row["screenshots"])
        self.assertFalse(row["has_note"])

    def test_a_blank_note_and_screenshots_that_are_not_images_do_not_count(self):
        broken = [{"name": "a.jpg"}, {"name": "b.jpg", "base64": "!!! not base64 !!!"}, "text", None, shot("c.jpg", JPEG_ONE)]
        report = self.post(report_body("   ", broken))

        row = self.row(report)

        self.assertEqual(1, row["screenshots"])
        self.assertFalse(row["has_note"])

    def test_a_report_that_is_not_json_is_still_listed_with_nothing_attached(self):
        report = self.post("this is not json")

        row = self.row(report)

        self.assertEqual(0, row["screenshots"])
        self.assertFalse(row["has_note"])
        self.assertEqual(200, self.client.get(f"/api/report/{report}/brief").status_code)

    def test_the_brief_carries_the_note_the_facts_and_where_the_screenshots_and_state_are(self):
        report = self.post(report_body("the queue jumped", [shot("screenshot-1.jpg", JPEG_ONE)]))

        brief = self.client.get(f"/api/report/{report}/brief").json()

        self.assertEqual(report, brief["id"])
        self.assertEqual("the queue jumped", brief["note"])
        self.assertEqual("0.1.950", brief["app_version"])
        self.assertEqual("Google Pixel 7", brief["device"])
        self.assertEqual("diagnostics", brief["kind"])
        self.assertEqual([{"n": 1, "name": "screenshot-1.jpg", "width": 90, "height": 160, "bytes": len(JPEG_ONE)}], brief["screenshots"])
        self.assertFalse(brief["has_state"])
        self.assertFalse(brief["considered"])

    def test_the_brief_says_when_the_state_dump_is_held(self):
        report = self.post(report_body())
        import gzip

        dump = gzip.compress(json.dumps({"kind": "totum-state-dump", "appVersion": "0.1.950"}).encode())
        self.assertEqual(202, self.client.post("/ingest/state", content=dump, headers={"X-Totum-Report-Id": report}).status_code)

        brief = self.client.get(f"/api/report/{report}/brief").json()

        self.assertTrue(brief["has_state"])
        self.assertEqual(len(dump), brief["state_bytes"])

    def test_a_screenshot_comes_back_as_the_image_that_was_attached(self):
        report = self.post(report_body(None, [shot("screenshot-1.jpg", JPEG_ONE), shot("screenshot-2.jpg", JPEG_TWO)]))

        first = self.client.get(f"/api/report/{report}/screenshot/1")
        second = self.client.get(f"/api/report/{report}/screenshot/2")

        self.assertEqual(200, first.status_code)
        self.assertEqual("image/jpeg", first.headers["content-type"])
        self.assertEqual(JPEG_ONE, first.content)
        self.assertEqual(JPEG_TWO, second.content)

    def test_a_screenshot_that_is_not_there_is_a_404(self):
        report = self.post(report_body(None, [shot("screenshot-1.jpg", JPEG_ONE)]))

        for n in (0, 2, -1):
            self.assertEqual(404, self.client.get(f"/api/report/{report}/screenshot/{n}").status_code, n)
        self.assertEqual(404, self.client.get("/api/report/20261114T000000-00000000/screenshot/1").status_code)
        self.assertEqual(404, self.client.get("/api/report/20261114T000000-00000000/brief").status_code)

    def test_reports_stored_before_the_columns_existed_are_counted_at_start_up(self):
        report_id = f"{datetime.now(timezone.utc):%Y%m%dT%H%M%S}-{uuid.uuid4().hex[:8]}"
        path = Path(main.REPORTS_DIR) / f"{report_id}.json"
        path.write_text(report_body("old note", [shot("screenshot-1.jpg", JPEG_ONE)]))
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute(
                "INSERT INTO reports (id, received_at, path, kind) VALUES (?,?,?,?)",
                (report_id, datetime.now(timezone.utc).isoformat(), str(path), "diagnostics"),
            )

        main._backfill_report_facts()

        row = self.row(report_id)
        self.assertEqual(1, row["screenshots"])
        self.assertTrue(row["has_note"])


@unittest.skipIf(main is None, "needs fastapi and httpx")
class ShotsTest(unittest.TestCase):
    def test_a_name_cannot_climb_out_of_the_folder_it_is_written_to(self):
        entries = attachments.of({"screenshots": [shot("../../etc/passwd.jpg", JPEG_ONE), shot("", JPEG_TWO)]})

        self.assertEqual(["passwd.jpg", "screenshot-2.jpg"], [e["name"] for e in entries])

    def test_only_entries_with_decodable_data_are_kept_and_decode_gives_the_bytes(self):
        entries = attachments.of({"screenshots": [shot("a.jpg", JPEG_ONE), {"base64": 5}, {"base64": ""}]})

        self.assertEqual([JPEG_ONE], [e["data"] for e in entries])

    def test_a_payload_with_no_list_has_none(self):
        self.assertEqual([], attachments.of({"screenshots": "nope"}))
        self.assertEqual([], attachments.of(None))
        self.assertEqual("", attachments.note_of({"note": 5}))
        self.assertEqual("hello", attachments.note_of({"note": " hello "}))


if __name__ == "__main__":
    unittest.main()
