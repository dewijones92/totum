"""Crash and diagnostics sink for Totum.

Design rules, in priority order:

1. **Never lose a report.** The raw body is written to disk *before* anything is
   parsed or indexed. A malformed, truncated or unexpected payload is still stored
   and still visible — a crash report you dropped because it didn't validate is
   worse than useless.
2. **Readable without tooling.** Reports land as plain files under a bind-mounted
   directory, so they can be read over SSH with `cat` even if this service is down.
3. **Searchable.** Key fields are mirrored into SQLite so "every crash on commit
   abc123" or "group by exception" is one query, which is how you actually find a
   pattern rather than eyeballing files.
"""

from __future__ import annotations

import json
import os
import sqlite3
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse
from fastapi.templating import Jinja2Templates

DATA_DIR = Path(os.environ.get("CRASHLOG_DATA", "/data"))
REPORTS_DIR = DATA_DIR / "reports"
DB_PATH = DATA_DIR / "index.db"

# Generous: a verbose report with a few hundred events is still tens of KB, and the
# instruction is to prioritise collecting data. This only stops a runaway upload.
MAX_BODY_BYTES = 8 * 1024 * 1024

# Oldest reports are pruned past this so the Pi's disk can't fill.
MAX_TOTAL_MB = int(os.environ.get("CRASHLOG_MAX_TOTAL_MB", "512"))

TEMPLATES = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))

app = FastAPI(title="Totum crash log", docs_url="/api/docs", redoc_url=None)


def _connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def _init_storage() -> None:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    with closing(_connect()) as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS reports (
                id             TEXT PRIMARY KEY,
                received_at    TEXT NOT NULL,
                path           TEXT NOT NULL,
                kind           TEXT,
                app_version    TEXT,
                git_commit     TEXT,
                exception      TEXT,
                message        TEXT,
                device         TEXT,
                android        TEXT,
                install_id     TEXT,
                event_count    INTEGER,
                bytes          INTEGER,
                parsed         INTEGER NOT NULL DEFAULT 1,
                -- Triage. `new` until someone judges it; see TRIAGE_STATES.
                state          TEXT NOT NULL DEFAULT 'new',
                -- The version a fix landed in, so a recurrence AFTER it is obviously a
                -- regression rather than another copy of a known bug.
                fixed_in       TEXT,
                -- One line of why, so a later session inherits the reasoning instead of
                -- re-deriving it.
                note           TEXT,
                triaged_at     TEXT
            )
            """
        )
        # Migration for databases created before triage existed. ALTER TABLE ADD COLUMN is
        # the only schema change SQLite does cheaply, and each is idempotent via the guard.
        existing = {r["name"] for r in connection.execute("PRAGMA table_info(reports)")}
        for column, ddl in (
            ("state", "TEXT NOT NULL DEFAULT 'new'"),
            ("fixed_in", "TEXT"),
            ("note", "TEXT"),
            ("triaged_at", "TEXT"),
        ):
            if column not in existing:
                connection.execute(f"ALTER TABLE reports ADD COLUMN {column} {ddl}")
        connection.execute("CREATE INDEX IF NOT EXISTS reports_received ON reports(received_at DESC)")
        connection.execute("CREATE INDEX IF NOT EXISTS reports_commit ON reports(git_commit)")
        connection.execute("CREATE INDEX IF NOT EXISTS reports_exception ON reports(exception)")
        connection.execute("CREATE INDEX IF NOT EXISTS reports_state ON reports(state)")
        # One report usually holds several separate things (the bug Dewi wrote about, an old
        # crash still in the logcat buffer, a noisy sync line), and one verdict per report hid
        # which of them had been judged. A finding is one of those things, judged on its own.
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS findings (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                report_id   TEXT NOT NULL,
                title       TEXT NOT NULL,
                state       TEXT NOT NULL DEFAULT 'new',
                fixed_in    TEXT,
                note        TEXT,
                created_at  TEXT NOT NULL,
                updated_at  TEXT NOT NULL
            )
            """
        )
        connection.execute("CREATE INDEX IF NOT EXISTS findings_report ON findings(report_id)")
        connection.commit()


_init_storage()


def _dig(payload: Any, *keys: str) -> str | None:
    """First present, non-empty value among [keys], searched case-insensitively.

    Deliberately forgiving about the payload's shape: the app's report format will
    change, and a field rename should degrade to "unknown" rather than 500.
    """
    if not isinstance(payload, dict):
        return None
    lowered = {str(k).lower(): v for k, v in payload.items()}
    for key in keys:
        value = lowered.get(key.lower())
        if isinstance(value, (str, int, float)) and str(value).strip():
            return str(value)
    return None


def _prune() -> None:
    files = sorted(REPORTS_DIR.rglob("*.json"), key=lambda p: p.stat().st_mtime)
    total = sum(f.stat().st_size for f in files)
    limit = MAX_TOTAL_MB * 1024 * 1024
    while files and total > limit:
        oldest = files.pop(0)
        total -= oldest.stat().st_size
        oldest.unlink(missing_ok=True)
        with closing(_connect()) as connection:
            connection.execute(
                "DELETE FROM findings WHERE report_id IN (SELECT id FROM reports WHERE path = ?)",
                (str(oldest),),
            )
            connection.execute("DELETE FROM reports WHERE path = ?", (str(oldest),))
            connection.commit()


@app.post("/ingest")
async def ingest(request: Request) -> JSONResponse:
    """Accept a report. Stores first, parses second — a bad payload is still kept."""
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        body = body[:MAX_BODY_BYTES]

    now = datetime.now(timezone.utc)
    report_id = f"{now.strftime('%Y%m%dT%H%M%S')}-{uuid.uuid4().hex[:8]}"
    day_dir = REPORTS_DIR / now.strftime("%Y-%m-%d")
    day_dir.mkdir(parents=True, exist_ok=True)
    path = day_dir / f"{report_id}.json"
    path.write_bytes(body)

    parsed: Any = None
    try:
        parsed = json.loads(body.decode("utf-8", errors="replace"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        parsed = None

    events = parsed.get("events") if isinstance(parsed, dict) else None
    with closing(_connect()) as connection:
        connection.execute(
            """
            INSERT INTO reports (id, received_at, path, kind, app_version, git_commit,
                                 exception, message, device, android, install_id,
                                 event_count, bytes, parsed)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                report_id,
                now.isoformat(),
                str(path),
                _dig(parsed, "kind", "reportKind") or "unknown",
                _dig(parsed, "appVersion", "versionName", "version"),
                _dig(parsed, "gitCommit", "commit", "buildCommit"),
                _dig(parsed, "exception", "exceptionClass", "throwable"),
                _dig(parsed, "message", "exceptionMessage"),
                _dig(parsed, "device", "model", "deviceModel"),
                _dig(parsed, "android", "androidVersion", "sdk"),
                _dig(parsed, "installId", "installationId"),
                len(events) if isinstance(events, list) else None,
                len(body),
                1 if parsed is not None else 0,
            ),
        )
        connection.commit()
    _prune()
    # The app must never block or retry on our response body; a bare id is plenty.
    return JSONResponse({"id": report_id}, status_code=202)


#: What a report can be judged as.
#:
#: `noise` earns its place alongside `fixed`: on 2026-07-28 one "crash" was Claude's own
#: `adb` killing the app during testing, and another eleven were an already-fixed R8 bug.
#: Both cost real attention. A report that has been judged worthless should never cost it
#: again.
TRIAGE_STATES = ("new", "triaged", "fixed", "wontfix", "noise")


def _signature(row: sqlite3.Row) -> str:
    """What makes two reports "the same bug", for grouping.

    Exception plus the first line of the message. Deliberately coarse: eleven reports of one
    already-fixed crash should read as one row, and "11 crashes" that are all one build is a
    very different story from 11 spread across three.
    """
    exception = row["exception"] or row["kind"] or "unknown"
    message = (row["message"] or "").strip().splitlines()
    return f"{exception}: {message[0][:80]}" if message else exception


@app.post("/api/report/{report_id}/triage")
def api_triage(
    report_id: str,
    state: str = Query(...),
    fixed_in: str | None = Query(default=None),
    note: str | None = Query(default=None),
) -> JSONResponse:
    """Record a judgement. The point of the whole feature: 26 of 27 reports were unread on
    2026-07-28 because nothing tracked whether anyone had looked."""
    if state not in TRIAGE_STATES:
        return JSONResponse({"error": f"state must be one of {TRIAGE_STATES}"}, status_code=400)
    with closing(_connect()) as connection:
        updated = connection.execute(
            "UPDATE reports SET state = ?, fixed_in = ?, note = ?, triaged_at = ? WHERE id = ?",
            (state, fixed_in, note, datetime.now(timezone.utc).isoformat(), report_id),
        ).rowcount
        connection.commit()
    if not updated:
        return JSONResponse({"error": "no such report"}, status_code=404)
    return JSONResponse({"id": report_id, "state": state, "fixed_in": fixed_in, "note": note})


@app.post("/api/triage/signature")
def api_triage_signature(
    exception: str = Query(...),
    state: str = Query(...),
    fixed_in: str | None = Query(default=None),
    note: str | None = Query(default=None),
) -> JSONResponse:
    """Judge every report sharing an exception at once — because they usually arrive in
    elevens, and triaging them one at a time is how they end up untriaged."""
    if state not in TRIAGE_STATES:
        return JSONResponse({"error": f"state must be one of {TRIAGE_STATES}"}, status_code=400)
    with closing(_connect()) as connection:
        updated = connection.execute(
            "UPDATE reports SET state = ?, fixed_in = ?, note = ?, triaged_at = ? WHERE exception = ?",
            (state, fixed_in, note, datetime.now(timezone.utc).isoformat(), exception),
        ).rowcount
        connection.commit()
    return JSONResponse({"exception": exception, "state": state, "updated": updated})


def _findings(connection: sqlite3.Connection, report_id: str) -> list[dict[str, Any]]:
    return [
        dict(r)
        for r in connection.execute("SELECT * FROM findings WHERE report_id = ? ORDER BY id", (report_id,))
    ]


def _considered(report: sqlite3.Row, findings: list[dict[str, Any]]) -> bool:
    """Every finding judged, or (for a report triaged before findings existed) the report itself."""
    if findings:
        return all(f["state"] != "new" for f in findings)
    return report["state"] != "new"


def _summarise(connection: sqlite3.Connection, report_id: str) -> None:
    """Keeps the report's own state in step with its findings, so the list, the filters and
    /api/unread stay true without knowing findings exist: any unjudged finding leaves the report
    `new`; one shared verdict becomes the report's; a mix reads `triaged`."""
    findings = _findings(connection, report_id)
    if not findings:
        return
    states = {f["state"] for f in findings}
    if "new" in states:
        state = "new"
    elif len(states) == 1:
        state = states.pop()
    else:
        state = "triaged"
    fixed = {f["fixed_in"] for f in findings if f["state"] == "fixed" and f["fixed_in"]}
    fixed_in = fixed.pop() if len(fixed) == 1 else None
    note = "; ".join(f"{f['state']}: {f['title']}" for f in findings)
    connection.execute(
        "UPDATE reports SET state = ?, fixed_in = ?, note = ?, triaged_at = ? WHERE id = ?",
        (state, fixed_in, note, datetime.now(timezone.utc).isoformat(), report_id),
    )


@app.post("/api/report/{report_id}/findings")
def api_add_finding(
    report_id: str,
    title: str = Query(..., min_length=1),
    state: str = Query(default="new"),
    fixed_in: str | None = Query(default=None),
    note: str | None = Query(default=None),
) -> JSONResponse:
    if state not in TRIAGE_STATES:
        return JSONResponse({"error": f"state must be one of {TRIAGE_STATES}"}, status_code=400)
    now = datetime.now(timezone.utc).isoformat()
    with closing(_connect()) as connection:
        if connection.execute("SELECT 1 FROM reports WHERE id = ?", (report_id,)).fetchone() is None:
            return JSONResponse({"error": "no such report"}, status_code=404)
        finding_id = connection.execute(
            "INSERT INTO findings (report_id, title, state, fixed_in, note, created_at, updated_at) "
            "VALUES (?,?,?,?,?,?,?)",
            (report_id, title, state, fixed_in, note, now, now),
        ).lastrowid
        _summarise(connection, report_id)
        connection.commit()
        finding = connection.execute("SELECT * FROM findings WHERE id = ?", (finding_id,)).fetchone()
    return JSONResponse(dict(finding), status_code=201)


@app.post("/api/finding/{finding_id}")
def api_update_finding(
    finding_id: int,
    state: str = Query(...),
    title: str | None = Query(default=None),
    fixed_in: str | None = Query(default=None),
    note: str | None = Query(default=None),
) -> JSONResponse:
    if state not in TRIAGE_STATES:
        return JSONResponse({"error": f"state must be one of {TRIAGE_STATES}"}, status_code=400)
    with closing(_connect()) as connection:
        existing = connection.execute("SELECT * FROM findings WHERE id = ?", (finding_id,)).fetchone()
        if existing is None:
            return JSONResponse({"error": "no such finding"}, status_code=404)
        connection.execute(
            "UPDATE findings SET state = ?, title = ?, fixed_in = ?, note = ?, updated_at = ? WHERE id = ?",
            (
                state,
                title or existing["title"],
                fixed_in if fixed_in is not None else existing["fixed_in"],
                note if note is not None else existing["note"],
                datetime.now(timezone.utc).isoformat(),
                finding_id,
            ),
        )
        _summarise(connection, existing["report_id"])
        connection.commit()
        finding = connection.execute("SELECT * FROM findings WHERE id = ?", (finding_id,)).fetchone()
    return JSONResponse(dict(finding))


@app.get("/api/report/{report_id}/findings")
def api_report_findings(report_id: str) -> JSONResponse:
    with closing(_connect()) as connection:
        report = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
        if report is None:
            return JSONResponse({"error": "no such report"}, status_code=404)
        findings = _findings(connection, report_id)
    return JSONResponse(
        {
            "id": report_id,
            "app_version": report["app_version"],
            "state": report["state"],
            "considered": _considered(report, findings),
            "findings": findings,
        }
    )


@app.get("/api/considered")
def api_considered(since: str = Query(default="", description="received_at >= this, e.g. 2026-10-01")) -> JSONResponse:
    """Every report since a date with whether it has been considered and what was found in it —
    the ticked-off list, newest first."""
    with closing(_connect()) as connection:
        rows = connection.execute(
            "SELECT * FROM reports WHERE received_at >= ? ORDER BY received_at DESC", (since,)
        ).fetchall()
        out = []
        for row in rows:
            findings = _findings(connection, row["id"])
            out.append(
                {
                    "id": row["id"],
                    "kind": row["kind"],
                    "app_version": row["app_version"],
                    "state": row["state"],
                    "considered": _considered(row, findings),
                    "note": row["note"],
                    "findings": findings,
                }
            )
    return JSONResponse({"reports": out, "unconsidered": sum(not r["considered"] for r in out)})


@app.get("/api/unread")
def api_unread() -> JSONResponse:
    """How many reports nobody has judged, grouped by signature.

    The single number that would have surfaced 26 unread reports, and the grouping that
    turns "11 crashes" into "one bug, 11 times, on one build".
    """
    with closing(_connect()) as connection:
        rows = connection.execute("SELECT * FROM reports WHERE state = 'new'").fetchall()
    groups: dict[str, dict[str, Any]] = {}
    for row in rows:
        group = groups.setdefault(
            _signature(row),
            {"count": 0, "versions": set(), "newest": "", "ids": []},
        )
        group["count"] += 1
        group["versions"].add(row["app_version"] or "unknown")
        group["newest"] = max(group["newest"], row["received_at"] or "")
        group["ids"].append(row["id"])
    return JSONResponse(
        {
            "unread": len(rows),
            "groups": sorted(
                (
                    {
                        "signature": signature,
                        "count": g["count"],
                        # Sorted so the report reads deterministically, and so a fix landing
                        # in a later build is obvious from the list alone.
                        "versions": sorted(g["versions"]),
                        "newest": g["newest"],
                        "ids": g["ids"][:MAX_LISTED_IDS],
                    }
                    for signature, g in groups.items()
                ),
                key=lambda g: -g["count"],
            ),
        }
    )


@app.get("/healthz")
def healthz() -> PlainTextResponse:
    return PlainTextResponse("ok")


@app.get("/", response_class=HTMLResponse)
def index(
    request: Request,
    commit: str | None = None,
    exception: str | None = None,
    state: str | None = None,
    limit: int = Query(default=100, le=1000),
) -> HTMLResponse:
    clauses, params = [], []
    if state:
        clauses.append("state = ?")
        params.append(state)
    if commit:
        clauses.append("git_commit LIKE ?")
        params.append(f"{commit}%")
    if exception:
        clauses.append("exception LIKE ?")
        params.append(f"%{exception}%")
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    with closing(_connect()) as connection:
        rows = connection.execute(
            f"SELECT * FROM reports {where} ORDER BY received_at DESC LIMIT ?",
            (*params, limit),
        ).fetchall()
        totals = connection.execute(
            "SELECT exception, COUNT(*) AS n FROM reports GROUP BY exception ORDER BY n DESC LIMIT 10"
        ).fetchall()
        unread = connection.execute("SELECT COUNT(*) AS n FROM reports WHERE state = 'new'").fetchone()["n"]
    return TEMPLATES.TemplateResponse(
        request=request,
        name="index.html",
        context={
            "rows": rows,
            "totals": totals,
            "commit": commit or "",
            "exception": exception or "",
            "state": state or "",
            "unread": unread,
        },
    )


@app.get("/report/{report_id}", response_class=HTMLResponse)
def report(request: Request, report_id: str) -> HTMLResponse:
    with closing(_connect()) as connection:
        row = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
    if row is None:
        return HTMLResponse("<h1>No such report</h1>", status_code=404)
    raw = Path(row["path"]).read_text(encoding="utf-8", errors="replace")
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        payload = None
    return TEMPLATES.TemplateResponse(
        request=request,
        name="report.html",
        context={"row": row, "payload": payload, "raw": raw},
    )


@app.get("/report/{report_id}/raw")
def report_raw(report_id: str) -> PlainTextResponse:
    with closing(_connect()) as connection:
        row = connection.execute("SELECT path FROM reports WHERE id = ?", (report_id,)).fetchone()
    if row is None:
        return PlainTextResponse("not found", status_code=404)
    return PlainTextResponse(Path(row["path"]).read_text(encoding="utf-8", errors="replace"))


@app.get("/api/reports")
def api_reports(limit: int = Query(default=50, le=1000)) -> JSONResponse:
    """Machine-readable list — how Claude triages without opening a browser."""
    with closing(_connect()) as connection:
        rows = connection.execute(
            "SELECT * FROM reports ORDER BY received_at DESC LIMIT ?", (limit,)
        ).fetchall()
    return JSONResponse([dict(r) for r in rows])


MAX_LISTED_IDS = 5


@app.get("/latest", response_class=PlainTextResponse)
def latest() -> PlainTextResponse:
    """The newest report as plain text — the one-command triage over SSH or curl."""
    with closing(_connect()) as connection:
        row = connection.execute("SELECT * FROM reports ORDER BY received_at DESC LIMIT 1").fetchone()
    if row is None:
        return PlainTextResponse("no reports yet")
    raw = Path(row["path"]).read_text(encoding="utf-8", errors="replace")
    try:
        pretty = json.dumps(json.loads(raw), indent=2)
    except json.JSONDecodeError:
        pretty = raw
    header = f"# {row['id']}  {row['received_at']}  {row['app_version']} @ {row['git_commit']}\n\n"
    return PlainTextResponse(header + pretty)
