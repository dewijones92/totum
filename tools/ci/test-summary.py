#!/usr/bin/env python3
"""Turn the JUnit XML every test task already writes into something readable.

GitHub Actions does not parse test results: the run page tells you a job failed and leaves you
to open the log and scroll. This writes a table to the job summary instead — totals per module,
and every failure with its message — plus `::error` annotations so failures appear in the run's
annotation list without downloading anything.

Deliberately dependency-free and not a third-party action: it reads files we already produce,
needs no extra token permissions, and cannot break because somebody else re-tagged a release.

Usage:
    test-summary.py <title> <root...>          # writes to $GITHUB_STEP_SUMMARY, or stdout
Exit code is always 0: this reports on a build, it does not judge it. The test task itself is
what fails the job, and a broken summariser must never be the reason a green build goes red.
"""
import os
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

# A failure message is often a whole assertion diff plus a stack. Enough to recognise the
# problem, short enough that thirty failures still render.
MESSAGE_CHARS = 300
MAX_LISTED_FAILURES = 50


class Suite:
    """One test class's results."""

    def __init__(self, name, tests, failures, errors, skipped, seconds):
        self.name = name
        self.tests = tests
        self.failures = failures
        self.errors = errors
        self.skipped = skipped
        self.seconds = seconds

    @property
    def bad(self):
        return self.failures + self.errors


def parse_file(path):
    """Suites in one XML file, or [] if it is unreadable.

    Unreadable rather than fatal on purpose: a truncated file (a killed emulator, a crashed
    worker) must not hide the results of every other module.
    """
    try:
        root = ElementTree.parse(path).getroot()
    except (ElementTree.ParseError, OSError):
        return []
    elements = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
    return [
        Suite(
            name=element.get("name", path.stem),
            tests=int(element.get("tests", 0)),
            failures=int(element.get("failures", 0)),
            errors=int(element.get("errors", 0)),
            skipped=int(element.get("skipped", 0)),
            seconds=float(element.get("time", 0) or 0),
        )
        for element in elements
    ]


def failures_in(path):
    """(class, test, message) for each failure or error in one file."""
    try:
        root = ElementTree.parse(path).getroot()
    except (ElementTree.ParseError, OSError):
        return []
    found = []
    for case in root.iter("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            text = (bad.get("message") or bad.text or "").strip().replace("\n", " ")
            found.append((case.get("classname", "?"), case.get("name", "?"), text[:MESSAGE_CHARS]))
    return found


def collect(roots):
    """Every JUnit file under any of [roots]. Sorted so the summary is stable run to run."""
    files = []
    for root in roots:
        base = Path(root)
        if base.is_file():
            files.append(base)
        elif base.is_dir():
            files.extend(sorted(base.rglob("*.xml")))
    return files


def render(title, files):
    suites = [suite for path in files for suite in parse_file(path)]
    failures = [failure for path in files for failure in failures_in(path)]
    total = sum(s.tests for s in suites)
    bad = sum(s.bad for s in suites)
    skipped = sum(s.skipped for s in suites)
    seconds = sum(s.seconds for s in suites)

    lines = []
    if not suites:
        # Said out loud. "No results" and "everything passed" look identical in a green job, and
        # a test task that silently stopped running is the worst kind of green.
        return [f"### {title}", "", "**No test results found.** Did the test task run?"]

    mark = "❌" if bad else "✅"
    lines.append(f"### {mark} {title}")
    lines.append("")
    lines.append(
        f"**{total - bad - skipped} passed**, **{bad} failed**, {skipped} skipped "
        f"in {seconds:.0f}s across {len(suites)} classes"
    )

    if failures:
        lines.append("")
        lines.append("| Test | Why |")
        lines.append("|---|---|")
        for classname, name, message in failures[:MAX_LISTED_FAILURES]:
            short = classname.rsplit(".", 1)[-1]
            lines.append(f"| `{short}.{name}` | {message or 'no message'} |")
        if len(failures) > MAX_LISTED_FAILURES:
            lines.append(f"| … | and {len(failures) - MAX_LISTED_FAILURES} more |")

    # The slowest classes, because a suite that doubles in time is worth noticing before it
    # becomes the reason nobody runs it locally.
    slowest = sorted(suites, key=lambda s: s.seconds, reverse=True)[:5]
    if slowest and slowest[0].seconds >= 1:
        lines.append("")
        lines.append("<details><summary>Slowest classes</summary>")
        lines.append("")
        for suite in slowest:
            lines.append(f"- `{suite.name.rsplit('.', 1)[-1]}` — {suite.seconds:.1f}s ({suite.tests} tests)")
        lines.append("")
        lines.append("</details>")
    return lines


def annotate(files):
    """`::error` per failure, so they show up in the run's annotations too."""
    for path in files:
        for classname, name, message in failures_in(path):
            short = classname.rsplit(".", 1)[-1]
            print(f"::error title={short}.{name}::{message or 'failed'}")


def main(argv):
    if len(argv) < 2:
        print("usage: test-summary.py <title> <root...>", file=sys.stderr)
        return 0
    title, roots = argv[0], argv[1:]
    files = collect(roots)
    text = "\n".join(render(title, files)) + "\n"
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text)
        annotate(files)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
