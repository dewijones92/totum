#!/usr/bin/env python3
"""Watches whether YouTube will still serve this app a whole stream, and says when that changes.

Why a canary at all, separate from the test suite: **the thing that broke Totum on 2026-08-18 was
not a commit.** YouTube began refusing the app's streams past roughly their first megabyte, and the
app's code was byte-identical to the day before, when it worked. A test that runs on push therefore
cannot warn in time — there may be no push for a week, and the first person to find out is Dewi with
his headphones in.

So this runs on a clock, from his own home connection (a datacentre IP gets bot-checked and would
report a failure that is really an environment), and it reports **state changes** rather than results:

    working -> broken   the app has just stopped being able to stream. Act.
    broken  -> working  it has recovered, or a fix landed. Also act — probably to stop working around it.
    no change           silent. A watcher that shouts every hour is a watcher that gets muted.

It deliberately does NOT reuse the app's own live test. That test needs a JDK the Pi does not have,
and more importantly the two are asking different questions: the test asks "does our code work", this
asks "is the environment still one our code can work in". Keeping the probe independent is what makes
a disagreement between them meaningful.

What it checks is the narrowest thing that failed: take a real audio URL for a long public-domain
video, and range-fetch a megabyte from DEEP inside the file. That single request distinguishes the
failure from health — the first megabyte kept working throughout, which is exactly why every
existing check missed it.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

# NASA's "Moonbound Episode 2": 37 minutes, public domain, and a VOD rather than a live recording.
# Long enough that a megabyte-sized cap cannot hide inside it — the same fixture the app's live test
# uses, chosen after the first attempt used a retired live stream and passed while measuring nothing.
VIDEO_ID = "ttiLcMUQq80"

# Deep enough to be past any plausible trial window, and cheap: one megabyte, once per run.
DEEP_OFFSET = 8 * 1024 * 1024
RANGE_BYTES = 1024 * 1024

TIMEOUT_SECONDS = 60


def audio_url(video_id: str) -> str | None:
    """A direct audio URL for the video, via yt-dlp — whatever client it can still get one from."""
    try:
        done = subprocess.run(
            ["yt-dlp", "-f", "bestaudio", "--get-url", f"https://www.youtube.com/watch?v={video_id}"],
            capture_output=True,
            text=True,
            timeout=TIMEOUT_SECONDS * 4,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"[canary] could not run yt-dlp: {error}", file=sys.stderr)
        return None
    for line in done.stdout.splitlines():
        if line.startswith("http"):
            return line.strip()
    print(f"[canary] yt-dlp gave no URL: {done.stderr.strip()[-300:]}", file=sys.stderr)
    return None


def deep_fetch_status(url: str) -> int | None:
    """The HTTP status of a range request well inside the file, or None when it could not be made."""
    first = DEEP_OFFSET
    last = DEEP_OFFSET + RANGE_BYTES - 1
    request = urllib.request.Request(url, headers={"Range": f"bytes={first}-{last}"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status
    except urllib.error.HTTPError as error:
        # THE case this exists for. A 403 here with a 206 at offset zero is the exact signature of
        # 2026-08-18, and it is the one shape a naive "can we fetch anything" check calls healthy.
        return error.code
    except (urllib.error.URLError, OSError) as error:
        print(f"[canary] the deep request could not be made: {error}", file=sys.stderr)
        return None


def verdict() -> tuple[str, str]:
    """One of working / broken / unknown, with a sentence saying how it was decided."""
    url = audio_url(VIDEO_ID)
    if url is None:
        return "unknown", "no audio URL could be obtained, so nothing was measured"
    status = deep_fetch_status(url)
    if status is None:
        return "unknown", "the deep range request could not be made at all"
    if status in (200, 206):
        return "working", f"a 1MB range at {DEEP_OFFSET // (1024 * 1024)}MB returned {status}"
    return "broken", f"a 1MB range at {DEEP_OFFSET // (1024 * 1024)}MB returned {status}"


def notify(webhook: str | None, text: str) -> None:
    """Pushes [text] if a webhook is configured; the journal always has it either way.

    Journal-first on purpose: a canary whose only output is a push is a canary that goes silent when
    the push breaks, and nobody notices the silence.
    """
    print(f"[canary] {text}")
    if not webhook:
        return
    body = json.dumps({"text": text}).encode()
    request = urllib.request.Request(webhook, data=body, headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS).close()
    except (urllib.error.URLError, OSError) as error:
        print(f"[canary] could not push the alert: {error}", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    """[argv] is injectable so the tests can drive it without touching the real process arguments."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", default=str(Path.home() / ".totum-youtube-canary"))
    parser.add_argument(
        "--webhook",
        default=os.environ.get("TOTUM_CANARY_WEBHOOK"),
        help="Optional push target. Without one the verdict goes to the journal only.",
    )
    parser.add_argument(
        "--always-report",
        action="store_true",
        help="Say the verdict even when unchanged — for checking the canary itself is alive.",
    )
    args = parser.parse_args(argv)

    state_file = Path(args.state)
    was = state_file.read_text().strip() if state_file.exists() else "unknown"
    now, why = verdict()

    # An "unknown" is never recorded as a state change. It means the canary could not measure, which
    # is a fact about the canary and not about YouTube — treating it as a transition would produce a
    # working->broken alert every time the Pi's network hiccuped, and those are the alerts that teach
    # someone to ignore the channel.
    if now == "unknown":
        print(f"[canary] could not measure: {why} (last known: {was})")
        return 0

    if now != was:
        state_file.write_text(now)
        arrow = "RECOVERED" if now == "working" else "BROKEN"
        notify(args.webhook, f"Totum/YouTube {arrow}: {why} (was {was}). Video {VIDEO_ID}.")
        return 0

    if args.always_report:
        print(f"[canary] unchanged: {now} — {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
