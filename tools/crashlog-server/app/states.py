"""Whole-app state dumps from the phone, kept apart from the reports.

A dump that arrives with a report id is LINKED to that report (`<states>/linked/<report id>.json.gz`) and
kept for as long as the report is unconsidered; the caller deletes it with `release` the moment the report
is considered, and `enforce_budget` is the safety cap on what unconsidered reports may hold in total
(CRASHLOG_MAX_UNCONSIDERED_STATE_MB, default 1024). A dump with no report id (an older phone, or "Send my
state" on its own) is UNLINKED: a photograph with nothing to be judged against, so only the newest few are
kept (CRASHLOG_KEEP_STATES, default 5). Nothing here imports the web framework, so the rules can be tested
without it, and nothing here knows what "considered" means: that lives next to the findings.
"""

from __future__ import annotations

import gzip
import io
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

KIND = "totum-state-dump"
KEEP = int(os.environ.get("CRASHLOG_KEEP_STATES", "5"))
MAX_BYTES = int(os.environ.get("CRASHLOG_MAX_STATE_MB", "32")) * 1024 * 1024
MAX_UNPACKED = 8 * MAX_BYTES
BUDGET_BYTES = int(os.environ.get("CRASHLOG_MAX_UNCONSIDERED_STATE_MB", "1024")) * 1024 * 1024
SUFFIX = ".json.gz"
LINKED = "linked"
REPORT_ID = re.compile(r"^\d{8}T\d{6}-[0-9a-f]{8}$")
_NAME = re.compile(r"^(\d{8}T\d{6})(\d{6})Z-([^-]+)-(.+)\.json\.gz$")


class Refused(Exception):
    pass


def _clean(text: Any, fallback: str, limit: int) -> str:
    kept = re.sub(r"[^A-Za-z0-9._-]", "", str(text or ""))
    while ".." in kept:
        kept = kept.replace("..", "")
    kept = kept.strip(".-")[:limit]
    return kept or fallback


def _read(body: bytes, max_bytes: int, max_unpacked: int) -> dict[str, Any]:
    if len(body) > max_bytes:
        raise Refused(f"a state dump over {max_bytes} bytes is refused")
    if body[:2] != b"\x1f\x8b":
        raise Refused("a state dump must be gzip")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(body)) as unpacked:
            raw = unpacked.read(max_unpacked + 1)
    except (OSError, EOFError) as problem:
        raise Refused(f"the gzip could not be read: {problem}") from problem
    if len(raw) > max_unpacked:
        raise Refused(f"a state dump over {max_unpacked} bytes unpacked is refused")
    try:
        parsed = json.loads(raw)
    except (ValueError, UnicodeDecodeError) as problem:
        raise Refused(f"the dump is not JSON: {problem}") from problem
    if not isinstance(parsed, dict) or parsed.get("kind") != KIND:
        raise Refused(f"the dump is not a {KIND}")
    return parsed


def save(
    directory: Path,
    body: bytes,
    now: datetime,
    keep: int = KEEP,
    max_bytes: int = MAX_BYTES,
    max_unpacked: int = MAX_UNPACKED,
) -> Path:
    parsed = _read(body, max_bytes, max_unpacked)
    directory.mkdir(parents=True, exist_ok=True)
    install = _clean(parsed.get("installId"), "unknown", 8)
    version = _clean(parsed.get("appVersion"), "unknown", 24)
    target = directory / f"{now:%Y%m%dT%H%M%S%f}Z-{install}-{version}{SUFFIX}"
    partial = directory / (target.name + ".partial")
    partial.write_bytes(body)
    os.replace(partial, target)
    prune(directory, keep)
    return target


def _files(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return sorted(directory.glob(f"*{SUFFIX}"), key=lambda path: path.name, reverse=True)


def prune(directory: Path, keep: int) -> list[Path]:
    gone = _files(directory)[max(keep, 0):]
    for path in gone:
        path.unlink(missing_ok=True)
    return gone


def valid_report_id(text: Any) -> bool:
    return isinstance(text, str) and REPORT_ID.fullmatch(text) is not None


def linked_directory(directory: Path) -> Path:
    return directory / LINKED


def report_id_of(path: Path) -> str:
    return path.name[: -len(SUFFIX)]


def save_linked(
    directory: Path,
    report_id: str,
    body: bytes,
    max_bytes: int = MAX_BYTES,
    max_unpacked: int = MAX_UNPACKED,
) -> tuple[Path, bool]:
    if not valid_report_id(report_id):
        raise Refused("a report id is the server's own shape, like 20261114T090000-0a1b2c3d")
    _read(body, max_bytes, max_unpacked)
    folder = linked_directory(directory)
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / f"{report_id}{SUFFIX}"
    if target.exists():
        return target, False
    partial = folder / (target.name + ".partial")
    partial.write_bytes(body)
    os.replace(partial, target)
    return target, True


def linked_files(directory: Path) -> list[Path]:
    folder = linked_directory(directory)
    if not folder.is_dir():
        return []
    return sorted(folder.glob(f"*{SUFFIX}"), key=lambda path: (_received(path), path.name))


def linked_path(directory: Path, report_id: str) -> Path | None:
    if not valid_report_id(report_id):
        return None
    path = linked_directory(directory) / f"{report_id}{SUFFIX}"
    return path if path.is_file() else None


def release(directory: Path, report_id: str) -> int | None:
    path = linked_path(directory, report_id)
    if path is None:
        return None
    freed = path.stat().st_size
    path.unlink(missing_ok=True)
    return freed


def linked_bytes(directory: Path) -> int:
    return sum(path.stat().st_size for path in linked_files(directory))


def enforce_budget(directory: Path, budget_bytes: int) -> list[Path]:
    files = linked_files(directory)
    held = sum(path.stat().st_size for path in files)
    dropped = []
    while held > budget_bytes and len(files) > 1:
        oldest = files.pop(0)
        held -= oldest.stat().st_size
        oldest.unlink(missing_ok=True)
        dropped.append(oldest)
    return dropped


def _received(path: Path) -> float:
    match = _NAME.match(path.name)
    if match and path.parent.name != LINKED:
        return datetime.strptime(match.group(1) + match.group(2), "%Y%m%dT%H%M%S%f").replace(tzinfo=timezone.utc).timestamp()
    return path.stat().st_mtime


def _everything(directory: Path) -> list[Path]:
    return sorted(_files(directory) + linked_files(directory), key=lambda path: (_received(path), path.name), reverse=True)


def latest(directory: Path) -> Path | None:
    files = _everything(directory)
    return files[0] if files else None


def listing(directory: Path) -> list[dict[str, Any]]:
    rows = []
    for path in _everything(directory):
        match = _NAME.match(path.name)
        linked = path.parent.name == LINKED
        rows.append(
            {
                "name": path.name,
                "bytes": path.stat().st_size,
                "received": datetime.fromtimestamp(_received(path), timezone.utc).strftime("%Y-%m-%dT%H:%M:%S") + "Z",
                "report": report_id_of(path) if linked else None,
                "install": None if linked else (match.group(3) if match else None),
                "appVersion": None if linked else (match.group(4) if match else None),
            }
        )
    return rows
