"""Whole-app state dumps from the phone, kept apart from the reports.

A report is kept until somebody has considered it. A state dump is a photograph of the app at one
moment, big and quickly stale, so only the newest few are kept (CRASHLOG_KEEP_STATES, default 5).
Nothing here imports the web framework, so the rules can be tested without it.
"""

from __future__ import annotations

import gzip
import io
import json
import os
import re
from datetime import datetime
from pathlib import Path
from typing import Any

KIND = "totum-state-dump"
KEEP = int(os.environ.get("CRASHLOG_KEEP_STATES", "5"))
MAX_BYTES = int(os.environ.get("CRASHLOG_MAX_STATE_MB", "32")) * 1024 * 1024
MAX_UNPACKED = 8 * MAX_BYTES
SUFFIX = ".json.gz"
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


def latest(directory: Path) -> Path | None:
    files = _files(directory)
    return files[0] if files else None


def listing(directory: Path) -> list[dict[str, Any]]:
    rows = []
    for path in _files(directory):
        match = _NAME.match(path.name)
        rows.append(
            {
                "name": path.name,
                "bytes": path.stat().st_size,
                "received": (
                    datetime.strptime(match.group(1), "%Y%m%dT%H%M%S").isoformat() + "Z" if match else None
                ),
                "install": match.group(3) if match else None,
                "appVersion": match.group(4) if match else None,
            }
        )
    return rows
