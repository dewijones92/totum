#!/usr/bin/env python3
"""Fails if a live instrumented test is not registered in live-instrumented-tests.txt.

A live test absent from that list runs in the ordinary CI job, against a datacentre IP where YouTube
behaves differently — which is how `FourKActuallyPlaysTest` turned main red on 2026-08-18. The list is
read by both ci.yml (to exclude them) and live-test-via-home.sh (to run them), so an unregistered test
is both wrongly included and never covered.

Only the `video.live` package is checked. It is the convention for new live tests, and the four older
ones outside it are already listed; guessing which arbitrary class is "live" from its name would be a
worse rule than the convention.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LIST = ROOT / "tools/ci/live-instrumented-tests.txt"
LIVE_DIR = ROOT / "app/src/androidTest/java/com/dewijones92/totum/video/live"
PACKAGE = "com.dewijones92.totum.video.live"


def registered() -> set[str]:
    return {
        line.strip()
        for line in LIST.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    }


def main() -> int:
    if not LIVE_DIR.is_dir():
        print(f"no live test directory at {LIVE_DIR} — nothing to check")
        return 0
    known = registered()
    missing = sorted(
        f"{PACKAGE}.{path.stem}"
        for path in LIVE_DIR.glob("*.kt")
        if f"{PACKAGE}.{path.stem}" not in known
    )
    if missing:
        print("These live instrumented tests are NOT registered in tools/ci/live-instrumented-tests.txt:")
        for name in missing:
            print(f"  {name}")
        print()
        print("Add them there. Unregistered, they run in the ordinary CI job against a datacentre IP")
        print("where YouTube serves differently, and they are never run in the live phase either.")
        return 1
    print(f"all {len(list(LIVE_DIR.glob('*.kt')))} live instrumented tests are registered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
