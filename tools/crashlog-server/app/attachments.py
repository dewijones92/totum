from __future__ import annotations

import base64
import binascii
import os
from typing import Any


def of(payload: Any) -> list[dict[str, Any]]:
    listed = payload.get("screenshots") if isinstance(payload, dict) else None
    if not isinstance(listed, list):
        return []
    kept = []
    for entry in listed:
        encoded = entry.get("base64") if isinstance(entry, dict) else None
        if not isinstance(encoded, str) or not encoded:
            continue
        try:
            data = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError):
            continue
        if not data:
            continue
        name = os.path.basename(str(entry.get("name") or "")) or f"screenshot-{len(kept) + 1}.jpg"
        kept.append({"name": name, "width": entry.get("width"), "height": entry.get("height"), "data": data})
    return kept


def note_of(payload: Any) -> str:
    note = payload.get("note") if isinstance(payload, dict) else None
    return note.strip() if isinstance(note, str) else ""
