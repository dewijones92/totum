import gzip
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

DATA = tempfile.mkdtemp()
os.environ.setdefault("CRASHLOG_DATA", DATA)
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))

try:
    from fastapi.testclient import TestClient
    import main
except ImportError:  # the web framework and httpx are in the server image, not on every laptop
    main = None


def dump(version: str) -> bytes:
    return gzip.compress(
        json.dumps({"format": 1, "kind": "totum-state-dump", "appVersion": version, "installId": "abcd1234"}).encode()
    )


@unittest.skipIf(main is None, "needs fastapi and httpx")
class StateEndpointTest(unittest.TestCase):
    def setUp(self):
        shutil.rmtree(main.STATES_DIR, ignore_errors=True)
        self.client = TestClient(main.app)

    def test_a_dump_is_accepted_listed_and_downloadable_whole(self):
        body = dump("0.1.900")

        posted = self.client.post("/ingest/state", content=body, headers={"Content-Type": "application/gzip"})

        self.assertEqual(202, posted.status_code)
        listed = self.client.get("/api/states").json()
        self.assertEqual(["0.1.900"], [row["appVersion"] for row in listed])
        latest = self.client.get("/state/latest")
        self.assertEqual(200, latest.status_code)
        self.assertEqual(body, latest.content)

    def test_only_the_latest_five_are_kept_over_http(self):
        for n in range(8):
            self.assertEqual(202, self.client.post("/ingest/state", content=dump(f"0.1.{n}")).status_code)

        listed = self.client.get("/api/states").json()

        self.assertEqual(5, len(listed))
        self.assertEqual("0.1.7", listed[0]["appVersion"])

    def test_something_that_is_not_a_dump_is_a_400_and_stores_nothing(self):
        refused = self.client.post("/ingest/state", content=b"not gzip")

        self.assertEqual(400, refused.status_code)
        self.assertEqual([], self.client.get("/api/states").json())

    def test_no_dump_yet_is_a_404_not_an_error(self):
        self.assertEqual(404, self.client.get("/state/latest").status_code)

    def test_a_report_still_goes_to_ingest_and_is_not_mistaken_for_a_state(self):
        posted = self.client.post("/ingest", content=json.dumps({"kind": "crash", "message": "boom"}))

        self.assertEqual(202, posted.status_code)
        self.assertEqual([], self.client.get("/api/states").json())


if __name__ == "__main__":
    unittest.main()
