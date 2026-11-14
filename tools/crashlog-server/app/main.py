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

import ipaddress
import html
import re
import json
import os
import sqlite3
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse, Response
from fastapi.templating import Jinja2Templates

import attachments
import states

DATA_DIR = Path(os.environ.get("CRASHLOG_DATA", "/data"))
REPORTS_DIR = DATA_DIR / "reports"
STATES_DIR = DATA_DIR / "states"
DB_PATH = DATA_DIR / "index.db"

# Generous: a verbose report with a few hundred events is still tens of KB, and the
# instruction is to prioritise collecting data. This only stops a runaway upload.
MAX_BODY_BYTES = 8 * 1024 * 1024

# Oldest reports are pruned past this so the Pi's disk can't fill.
MAX_TOTAL_MB = int(os.environ.get("CRASHLOG_MAX_TOTAL_MB", "512"))

STATE_REPORT_HEADER = "x-totum-report-id"
STATE_BUDGET_BYTES = states.BUDGET_BYTES

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
            ("screenshot_count", "INTEGER"),
            ("has_note", "INTEGER"),
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


SCREEN_FILE = DATA_DIR / "screen.json"
SCREENS_FILE = DATA_DIR / "screens.json"
SCREEN_NETWORKS = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10")]
SCREEN_URL = re.compile(r"http://(\d{1,3}(?:\.\d{1,3}){3}):\d{1,5}/screen#key=[0-9a-z]{8,64}")
SCREEN_INSTALL_ID = re.compile(r"[0-9a-fA-F-]{8,64}")
SCREEN_FRESH_MINUTES = int(os.environ.get("CRASHLOG_SCREEN_FRESH_MINUTES", "75"))
SCREEN_MAX_PHONES = 8
SCREEN_UNNAMED = "unknown"
SCREEN_LEGACY = "legacy"


def _screen_text(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = "".join(c for c in value if c.isprintable()).strip()
    return cleaned[:limit] or None


def _screen_home_address(url: Any) -> bool:
    shape = SCREEN_URL.fullmatch(url) if isinstance(url, str) else None
    if shape is None:
        return False
    try:
        host = ipaddress.ip_address(shape.group(1))
    except ValueError:
        return False
    return any(host in network for network in SCREEN_NETWORKS)


def _read_phones() -> dict[str, dict[str, Any]]:
    """Every phone that has announced, by install id. A file from an older build, or a corrupt one, reads as empty."""
    try:
        known = json.loads(SCREENS_FILE.read_text()) if SCREENS_FILE.exists() else {}
    except (OSError, ValueError):
        print("dewidebug screen: screens.json unreadable; treating it as empty", flush=True)
        known = {}
    phones = known.get("phones") if isinstance(known, dict) else None
    phones = phones if isinstance(phones, dict) else {}
    if not phones and SCREEN_FILE.exists():
        try:
            single = json.loads(SCREEN_FILE.read_text())
        except (OSError, ValueError):
            single = None
        if isinstance(single, dict) and isinstance(single.get("url"), str) and isinstance(single.get("at"), str):
            phones = {
                SCREEN_LEGACY: {"url": single["url"], "at": single["at"], "model": None, "playing": False, "title": None}
            }
            print("dewidebug screen: read the single-phone screen.json left by an older build", flush=True)
    return {k: v for k, v in phones.items() if isinstance(k, str) and isinstance(v, dict)}


def remember_screen(parsed: Any) -> bool:
    """Keep where one phone serves its PC screen page; only a home or WireGuard address is accepted."""
    url = parsed.get("url") if isinstance(parsed, dict) else None
    if not _screen_home_address(url):
        print(f"dewidebug screen: refused an announce, url shape or address not accepted ({str(url)[:80]!r})", flush=True)
        return False
    raw_id = parsed.get("installId")
    install_id = raw_id if isinstance(raw_id, str) and SCREEN_INSTALL_ID.fullmatch(raw_id) else SCREEN_UNNAMED
    phones = _read_phones()
    phones.pop(SCREEN_LEGACY, None)
    phones[install_id] = {
        "url": url,
        "at": datetime.now(timezone.utc).isoformat(),
        "model": _screen_text(parsed.get("model"), 60),
        "playing": parsed.get("playing") is True,
        "title": _screen_text(parsed.get("title"), 120),
    }
    while len(phones) > SCREEN_MAX_PHONES:
        oldest = min(phones, key=lambda k: str(phones[k].get("at")))
        phones.pop(oldest)
        print(f"dewidebug screen: more than {SCREEN_MAX_PHONES} phones; forgot {oldest}", flush=True)
    SCREENS_FILE.write_text(json.dumps({"phones": phones}))
    print(
        f"dewidebug screen: announce from {install_id} model={phones[install_id]['model']!r} "
        f"playing={phones[install_id]['playing']} phones={len(phones)}",
        flush=True,
    )
    return True


def screen_phones(now: datetime | None = None) -> list[dict[str, Any]]:
    """Known phones, re-checked on the way out so a file written by an older build cannot redirect anywhere.

    Fresh first, then playing, then the most recently heard from. `fresh` means it announced within the window.
    """
    now = now or datetime.now(timezone.utc)
    listed = []
    for install_id, entry in _read_phones().items():
        if not _screen_home_address(entry.get("url")):
            continue
        try:
            heard = datetime.fromisoformat(str(entry.get("at")))
        except ValueError:
            continue
        age_minutes = max(0.0, (now - heard).total_seconds() / 60)
        listed.append({
            "id": install_id,
            "url": entry["url"],
            "model": _screen_text(entry.get("model"), 60),
            "title": _screen_text(entry.get("title"), 120),
            "age_minutes": age_minutes,
            "fresh": age_minutes <= SCREEN_FRESH_MINUTES,
            "playing": entry.get("playing") is True,
        })
    listed.sort(key=lambda p: (not p["fresh"], not p["playing"], p["age_minutes"]))
    return listed


def screen_decision(phones: list[dict[str, Any]], choose: bool = False) -> tuple[str, Any]:
    """`("redirect", url)`, `("choose", phones)` or `("none", None)`.

    One fresh phone goes straight there; with several, straight to the one that is playing when only one is;
    otherwise the chooser. `choose` (the phone page's "switch phone" link) always shows the chooser.
    """
    if not phones:
        return "none", None
    if not choose:
        fresh = [p for p in phones if p["fresh"]]
        if len(fresh) == 1:
            return "redirect", fresh[0]["url"]
        playing = [p for p in fresh if p["playing"]]
        if len(playing) == 1:
            return "redirect", playing[0]["url"]
    return "choose", phones


def screen_target() -> str | None:
    kind, value = screen_decision(screen_phones())
    return value if kind == "redirect" else None


def _ago(minutes: float) -> str:
    if minutes < 1:
        return "just now"
    if minutes < 90:
        return f"{int(minutes)} min ago"
    if minutes < 48 * 60:
        return f"{int(minutes // 60)} h ago"
    return f"{int(minutes // (24 * 60))} days ago"


def screen_page(phones: list[dict[str, Any]] | None = None) -> str:
    head = (
        "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
        "<title>Totum screen</title><body style='font:18px/1.5 sans-serif;padding:24px;background:#fff;color:#1a1c20'>"
    )
    if not phones:
        return (head + "<h1>📭 No phone yet</h1>"
                "<p>Open Totum on your phone on the same Wi-Fi, then reload this page.</p></body>")
    rows = []
    for phone in phones:
        name = html.escape(phone["model"] or "Phone")
        status = "▶️ Playing" if phone["playing"] else "⏸️ Not playing"
        title = f" · {html.escape(phone['title'])}" if phone["title"] else ""
        seen = f"{'✅' if phone['fresh'] else '💤'} heard {_ago(phone['age_minutes'])}"
        rows.append(
            f"<li style='margin:14px 0'><a href=\"{html.escape(phone['url'], quote=True)}\" "
            f"style='font-size:22px;font-weight:600;color:#e8590c'>📱 {name}</a><br>"
            f"<span style='color:#565b64'>{status}{title} · {seen}</span></li>")
    return head + "<h1>📱 Which phone?</h1><ul style='list-style:none;padding:0'>" + "".join(rows) + "</ul></body>"


@app.get("/screen", response_model=None)
def screen(choose: bool = Query(default=False)) -> Response:
    phones = screen_phones()
    kind, value = screen_decision(phones, choose)
    print(
        f"dewidebug screen: /screen choose={choose} phones={[(p['id'][:8], p['fresh'], p['playing']) for p in phones]} -> {kind}",
        flush=True,
    )
    if kind == "redirect":
        return RedirectResponse(value, status_code=302, headers={"Cache-Control": "no-store"})
    return HTMLResponse(screen_page(value), headers={"Cache-Control": "no-store"})


@app.post("/ingest")
async def ingest(request: Request) -> JSONResponse:
    """Accept a report. Stores first, parses second — a bad payload is still kept."""
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        body = body[:MAX_BODY_BYTES]
    try:
        announced = json.loads(body.decode("utf-8", errors="replace"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        announced = None
    if isinstance(announced, dict) and announced.get("kind") == "screen":
        kept = remember_screen(announced)
        return JSONResponse({"screen": "kept" if kept else "refused"}, status_code=202 if kept else 400)

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
                                 event_count, bytes, parsed, screenshot_count, has_note)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                len(attachments.of(parsed)),
                1 if attachments.note_of(parsed) else 0,
            ),
        )
        connection.commit()
    _prune()
    _sweep_states("a report arrived")
    # The app must never block or retry on our response body; a bare id is plenty.
    return JSONResponse({"id": report_id}, status_code=202)


@app.post("/ingest/state")
async def ingest_state(request: Request, report: str | None = Query(default=None)) -> JSONResponse:
    """Accept a whole-app state dump: linked to its report when it carries a report id, else the newest few are kept."""
    body = await request.body()
    report_id = (request.headers.get(STATE_REPORT_HEADER) or report or "").strip() or None
    if report_id is not None:
        return _ingest_linked_state(report_id, body)
    try:
        saved = states.save(STATES_DIR, body, datetime.now(timezone.utc))
    except states.Refused as refusal:
        print(f"dewidebug state: refused {len(body)} bytes: {refusal}", flush=True)
        return JSONResponse({"refused": str(refusal)}, status_code=400)
    print(
        f"dewidebug state: kept {saved.name} ({len(body)} bytes) with no report id; "
        f"{len(states.listing(STATES_DIR))} held",
        flush=True,
    )
    return JSONResponse({"state": saved.name}, status_code=202)


def _ingest_linked_state(report_id: str, body: bytes) -> JSONResponse:
    if not states.valid_report_id(report_id):
        print(f"dewidebug state: refused {len(body)} bytes: {report_id[:40]!r} is not a report id", flush=True)
        return JSONResponse({"refused": "not a report id"}, status_code=400)
    with closing(_connect()) as connection:
        row = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
        if row is None:
            print(f"dewidebug state: refused {len(body)} bytes for {report_id}: no such report", flush=True)
            return JSONResponse({"refused": "no such report"}, status_code=404)
        findings = _findings(connection, report_id)
        if _considered(row, findings):
            print(
                f"dewidebug state: discarded {len(body)} bytes for {report_id}: the report is already considered "
                f"({row['state']}, {len(findings)} finding(s)), so its dump is not kept",
                flush=True,
            )
            return JSONResponse(
                {"report": report_id, "kept": False, "discarded": "report already considered"}, status_code=202
            )
    try:
        saved, created = states.save_linked(STATES_DIR, report_id, body)
    except states.Refused as refusal:
        print(f"dewidebug state: refused {len(body)} bytes for {report_id}: {refusal}", flush=True)
        return JSONResponse({"refused": str(refusal)}, status_code=400)
    if not created:
        print(
            f"dewidebug state: {report_id} already has its dump; this {len(body)} byte upload was not stored",
            flush=True,
        )
        return JSONResponse({"state": saved.name, "report": report_id, "duplicate": True}, status_code=200)
    print(
        f"dewidebug state: kept {saved.name} ({len(body)} bytes) for unconsidered report {report_id}; "
        f"{len(states.linked_files(STATES_DIR))} linked dump(s), {states.linked_bytes(STATES_DIR)} bytes held",
        flush=True,
    )
    _enforce_state_budget()
    return JSONResponse({"state": saved.name, "report": report_id}, status_code=202)


def _enforce_state_budget() -> None:
    held = states.linked_bytes(STATES_DIR)
    for path in states.enforce_budget(STATES_DIR, STATE_BUDGET_BYTES):
        print(
            f"dewidebug state: BUDGET EXCEEDED: {held} bytes of dumps for unconsidered reports against a budget of "
            f"{STATE_BUDGET_BYTES}; dropped the oldest, {path.name}, whose report is still unconsidered. "
            f"Consider the reports (triage.sh) or raise CRASHLOG_MAX_UNCONSIDERED_STATE_MB",
            flush=True,
        )


def _state_verdict(connection: sqlite3.Connection, report_id: str) -> str | None:
    row = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
    if row is None:
        return "its report no longer exists"
    findings = _findings(connection, report_id)
    if _considered(row, findings):
        return f"its report is considered (state {row['state']}, {len(findings)} finding(s))"
    return None


def _sweep_states(why: str) -> None:
    with closing(_connect()) as connection:
        for path in states.linked_files(STATES_DIR):
            report_id = states.report_id_of(path)
            reason = _state_verdict(connection, report_id)
            if reason is None:
                continue
            freed = states.release(STATES_DIR, report_id)
            print(f"dewidebug state: deleted linked dump {path.name} ({freed} bytes): {reason} [{why}]", flush=True)


def _state_rows() -> list[dict[str, Any]]:
    rows = states.listing(STATES_DIR)
    with closing(_connect()) as connection:
        for row in rows:
            if row["report"] is None:
                continue
            report = connection.execute(
                "SELECT app_version, install_id FROM reports WHERE id = ?", (row["report"],)
            ).fetchone()
            if report is not None:
                row["appVersion"] = report["app_version"]
                row["install"] = (report["install_id"] or "")[:8] or None
    return rows


@app.get("/api/states")
def api_states() -> JSONResponse:
    return JSONResponse(_state_rows())


@app.get("/api/report/{report_id}/state", response_model=None)
def api_report_state(report_id: str) -> Response:
    path = states.linked_path(STATES_DIR, report_id)
    if path is not None:
        return Response(
            path.read_bytes(),
            media_type="application/gzip",
            headers={"Content-Disposition": f'attachment; filename="{path.name}"'},
        )
    with closing(_connect()) as connection:
        row = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
        if row is None:
            return JSONResponse({"error": "no such report"}, status_code=404)
        considered = _considered(row, _findings(connection, report_id))
    why = (
        "the report is considered, so its dump was deleted (or it never had one)"
        if considered
        else "none held: it never arrived, or was dropped for the size budget"
    )
    return JSONResponse({"error": f"no state dump for this report: {why}", "considered": considered}, status_code=404)


@app.get("/state/latest", response_model=None)
def state_latest() -> Response:
    newest = states.latest(STATES_DIR)
    if newest is None:
        return PlainTextResponse("No state dump yet.", status_code=404)
    return Response(
        newest.read_bytes(),
        media_type="application/gzip",
        headers={"Content-Disposition": f'attachment; filename="{newest.name}"'},
    )


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
    _sweep_states(f"report {report_id} triaged as {state}")
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
    _sweep_states(f"{updated} report(s) of {exception} triaged as {state}")
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
    _sweep_states(f"finding added to {report_id} as {state}")
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
    _sweep_states(f"finding {finding_id} of {existing['report_id']} set to {state}")
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
            dump = states.linked_path(STATES_DIR, row["id"])
            out.append(
                {
                    "id": row["id"],
                    "kind": row["kind"],
                    "app_version": row["app_version"],
                    "state": row["state"],
                    "considered": _considered(row, findings),
                    "has_state": dump is not None,
                    "state_bytes": dump.stat().st_size if dump is not None else None,
                    "screenshots": row["screenshot_count"] or 0,
                    "has_note": bool(row["has_note"]),
                    "note": row["note"],
                    "findings": findings,
                }
            )
    return JSONResponse({"reports": out, "unconsidered": sum(not r["considered"] for r in out)})


def _backfill_report_facts() -> None:
    with closing(_connect()) as connection:
        rows = connection.execute(
            "SELECT id, path FROM reports WHERE screenshot_count IS NULL OR has_note IS NULL"
        ).fetchall()
        for row in rows:
            payload = _read_payload(row["path"])
            connection.execute(
                "UPDATE reports SET screenshot_count = ?, has_note = ? WHERE id = ?",
                (len(attachments.of(payload)), 1 if attachments.note_of(payload) else 0, row["id"]),
            )
        connection.commit()
    if rows:
        print(f"dewidebug report: counted the screenshots and note of {len(rows)} report(s) stored before they were recorded", flush=True)


def _read_payload(path: str) -> Any:
    try:
        return json.loads(Path(path).read_text(encoding="utf-8", errors="replace"))
    except (OSError, ValueError):
        return None


@app.get("/api/report/{report_id}/brief")
def api_report_brief(report_id: str) -> JSONResponse:
    with closing(_connect()) as connection:
        row = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
        if row is None:
            return JSONResponse({"error": "no such report"}, status_code=404)
        findings = _findings(connection, report_id)
    payload = _read_payload(row["path"])
    dump = states.linked_path(STATES_DIR, report_id)
    return JSONResponse(
        {
            "id": report_id,
            "kind": row["kind"],
            "received_at": row["received_at"],
            "app_version": row["app_version"],
            "git_commit": row["git_commit"],
            "device": row["device"],
            "android": row["android"],
            "exception": row["exception"],
            "message": row["message"],
            "note": attachments.note_of(payload),
            "screenshots": [
                {"n": n, "name": s["name"], "width": s["width"], "height": s["height"], "bytes": len(s["data"])}
                for n, s in enumerate(attachments.of(payload), start=1)
            ],
            "has_state": dump is not None,
            "state_bytes": dump.stat().st_size if dump is not None else None,
            "considered": _considered(row, findings),
            "findings": len(findings),
        }
    )


@app.get("/api/report/{report_id}/screenshot/{number}", response_model=None)
def api_report_screenshot(report_id: str, number: int) -> Response:
    with closing(_connect()) as connection:
        row = connection.execute("SELECT path FROM reports WHERE id = ?", (report_id,)).fetchone()
    if row is None:
        return JSONResponse({"error": "no such report"}, status_code=404)
    found = attachments.of(_read_payload(row["path"]))
    if not 1 <= number <= len(found):
        return JSONResponse({"error": f"this report has {len(found)} screenshot(s)"}, status_code=404)
    return Response(found[number - 1]["data"], media_type="image/jpeg")


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


_backfill_report_facts()
_sweep_states("start-up")
