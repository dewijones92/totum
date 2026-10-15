#!/usr/bin/env python3
"""Write a report's attached screenshots to files: screenshots.py <report.json> <out-dir>."""
import base64
import json
import os
import sys


def main(report_path, out_dir):
    report = json.load(open(report_path))
    shots = report.get("screenshots") or []
    os.makedirs(out_dir, exist_ok=True)
    for shot in shots:
        path = os.path.join(out_dir, os.path.basename(shot.get("name") or "screenshot.jpg"))
        with open(path, "wb") as f:
            f.write(base64.b64decode(shot["base64"]))
        print(f"{path} {shot.get('width')}x{shot.get('height')} {shot.get('bytes')}B")
    print(f"{len(shots)} screenshot(s); note: {report.get('note', '')[:120]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
