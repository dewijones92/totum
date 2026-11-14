#!/usr/bin/env python3
"""Write a report's attached screenshots to files: screenshots.py <report.json> <out-dir>."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "app"))
import attachments  # noqa: E402


def main(report_path, out_dir):
    report = json.load(open(report_path))
    shots = attachments.of(report)
    os.makedirs(out_dir, exist_ok=True)
    for shot in shots:
        path = os.path.join(out_dir, shot["name"])
        with open(path, "wb") as f:
            f.write(shot["data"])
        print(f"{path} {shot['width']}x{shot['height']} {len(shot['data'])}B")
    print(f"{len(shots)} screenshot(s); note: {attachments.note_of(report)[:120]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
